package compliance;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ingests trades from any number of concurrent connection-reader threads
 * (see ComplianceServer) via a BlockingQueue, and evaluates them against
 * every configured Rule on a single dedicated evaluation thread. A single
 * evaluation thread means rule implementations never need their own
 * synchronization for the rolling per-trader/per-instrument state they
 * keep -- the same "one writer, lock-free-by-construction" pattern used by
 * the matching-engine and market-data-decoder projects in this portfolio,
 * just with a plain BlockingQueue here instead of a hand-rolled lock-free
 * ring buffer, since Java trade volumes (thousands/sec) don't need one.
 */
public final class RuleEngine {
    private final List<Rule> rules;
    private final BlockingQueue<Trade> queue = new LinkedBlockingQueue<>();
    private final List<TradeListener> listeners = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong alertCount = new AtomicLong();
    private Thread evalThread;

    public RuleEngine(List<Rule> rules) {
        this.rules = rules;
    }

    public void addListener(TradeListener listener) {
        listeners.add(listener);
    }

    public void submit(Trade trade) {
        queue.add(trade);
    }

    public long processedCount() {
        return processedCount.get();
    }

    public long alertCount() {
        return alertCount.get();
    }

    public void start() {
        running.set(true);
        evalThread = new Thread(this::runLoop, "rule-engine-eval");
        evalThread.setDaemon(true);
        evalThread.start();
    }

    public void stop() {
        running.set(false);
        if (evalThread != null) evalThread.interrupt();
    }

    private void runLoop() {
        while (running.get() || !queue.isEmpty()) {
            Trade trade;
            try {
                trade = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                continue;
            }
            if (trade == null) continue;

            for (TradeListener l : listeners) l.onTrade(trade);
            for (Rule rule : rules) {
                Optional<Alert> alert = rule.evaluate(trade);
                if (alert.isPresent()) {
                    alertCount.incrementAndGet();
                    for (TradeListener l : listeners) l.onAlert(alert.get());
                }
            }
            processedCount.incrementAndGet();
        }
    }
}
