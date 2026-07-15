package simulator;

import compliance.ComplianceConfig;
import compliance.TradeCodec;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Connects to a running ComplianceServer over a real TCP socket and streams
 * synthetic executed trades. Trader assignment for ordinary flow round-robins
 * through the configured trader pool, which keeps each trader's baseline
 * rate low and even (aggregate rate / trader count) -- deliberately, so that
 * RapidFireRule's threshold means something. Occasionally a short "burst"
 * overrides the round robin and sends many consecutive trades from one
 * trader, which is what actually trips that rule; without a paced baseline,
 * any sufficiently fast unpaced simulator would trip it on nearly every
 * trade, making the rule meaningless as a signal.
 *
 * Output is paced to a target aggregate rate (see targetRatePerSec) using
 * batched sleeps, since RapidFireRule's window is measured against real
 * wall-clock ingest timestamps -- there's no way to make a real-time rate
 * limit meaningful without actually limiting the real-time rate.
 */
public final class TradeSimulator {
    private static final AtomicLong idCounter = new AtomicLong(1);
    private static final int BURST_LENGTH = 25;
    private static final double BURST_START_PROBABILITY = 0.0006;

    private final String host;
    private final int port;
    private final long numTrades;
    private final double targetRatePerSec;
    private final Random rng;
    private final Map<String, Double> refPrice = new HashMap<>();

    private int cycleIdx = 0;
    private String burstTrader = null;
    private int burstRemaining = 0;

    public TradeSimulator(String host, int port, long numTrades, long seed, double targetRatePerSec) {
        this.host = host;
        this.port = port;
        this.numTrades = numTrades;
        this.targetRatePerSec = targetRatePerSec;
        this.rng = new Random(seed);
        for (String instr : ComplianceConfig.INSTRUMENTS) {
            refPrice.put(instr, 20 + rng.nextDouble() * 480);
        }
    }

    public void run() throws IOException {
        try (Socket socket = new Socket(host, port);
             OutputStream rawOut = socket.getOutputStream();
             BufferedOutputStream out = new BufferedOutputStream(rawOut, 64 * 1024)) {

            final int batchSize = 20;
            final long batchTargetNanos = (long) (batchSize / targetRatePerSec * 1_000_000_000L);
            long batchStart = System.nanoTime();

            for (long i = 0; i < numTrades; i++) {
                String line = nextTradeLine();
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\n');

                if ((i + 1) % batchSize == 0) {
                    out.flush();
                    long elapsed = System.nanoTime() - batchStart;
                    long remaining = batchTargetNanos - elapsed;
                    if (remaining > 0) {
                        try {
                            Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    batchStart = System.nanoTime();
                }
            }
            out.flush();
        }
    }

    private String pickTrader() {
        if (burstRemaining > 0) {
            burstRemaining--;
            return burstTrader;
        }
        if (rng.nextDouble() < BURST_START_PROBABILITY) {
            burstTrader = ComplianceConfig.TRADERS.get(rng.nextInt(ComplianceConfig.TRADERS.size()));
            burstRemaining = BURST_LENGTH - 1;
            return burstTrader;
        }
        String t = ComplianceConfig.TRADERS.get(cycleIdx);
        cycleIdx = (cycleIdx + 1) % ComplianceConfig.TRADERS.size();
        return t;
    }

    private String nextTradeLine() {
        String trader = pickTrader();
        String instrument = ComplianceConfig.INSTRUMENTS.get(rng.nextInt(ComplianceConfig.INSTRUMENTS.size()));
        double ref = refPrice.get(instrument);
        double anomalyRoll = rng.nextDouble();
        char side = rng.nextBoolean() ? 'B' : 'S';
        double price;
        long qty;

        if (anomalyRoll < 0.003) {
            boolean up = rng.nextBoolean();
            double shock = 0.10 + rng.nextDouble() * 0.10;
            price = ref * (up ? (1 + shock) : (1 - shock));
            qty = 100 + rng.nextInt(900);
        } else if (anomalyRoll < 0.006) {
            price = ref * (0.98 + rng.nextDouble() * 0.04);
            qty = 5000 + rng.nextInt(20000);
        } else if (anomalyRoll < 0.009) {
            instrument = pickRestricted();
            ref = refPrice.getOrDefault(instrument, ref);
            price = ref * (0.99 + rng.nextDouble() * 0.02);
            qty = 100 + rng.nextInt(500);
        } else {
            price = ref * (0.98 + rng.nextDouble() * 0.04);
            qty = 10 + rng.nextInt(500);
        }

        price = Math.round(price * 100.0) / 100.0;
        refPrice.put(instrument, price);
        long tradeId = idCounter.getAndIncrement();
        return TradeCodec.encode(tradeId, trader, instrument, side, price, qty);
    }

    private String pickRestricted() {
        var list = new java.util.ArrayList<>(ComplianceConfig.RESTRICTED_INSTRUMENTS);
        return list.get(rng.nextInt(list.size()));
    }
}
