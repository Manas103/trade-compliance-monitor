package bench;

import compliance.Alert;
import compliance.ComplianceConfig;
import compliance.ComplianceServer;
import compliance.LatencyStats;
import compliance.RuleEngine;
import compliance.Trade;
import compliance.TradeListener;
import simulator.TradeSimulator;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Headless, measurable end-to-end run: a real ComplianceServer (TCP,
 * java.net.ServerSocket) and a TradeSimulator client run as separate
 * threads within one JVM, connected over a genuine loopback TCP socket --
 * not an in-memory function call. Running both in one process is what lets
 * this program report meaningful latency numbers: alert latency is
 * measured from Trade.ingestTsNanos (stamped the instant the server reads
 * the line off the socket) to Alert.detectedTsNanos (stamped when a rule
 * fires), both using System.nanoTime() from the same JVM -- nanoTime has
 * no defined epoch and isn't reliably comparable across separate JVM
 * processes, so this is the honest way to get a real socket-to-alert
 * latency number rather than faking it with a shared clock assumption.
 *
 * Usage: java bench.BenchmarkMain [numTrades] [ratePerSec]
 */
public final class BenchmarkMain {
    public static void main(String[] args) throws Exception {
        long numTrades = args.length > 0 ? Long.parseLong(args[0]) : 100_000;
        double rate = args.length > 1 ? Double.parseDouble(args[1]) : 900.0;

        AtomicLong tradesSeen = new AtomicLong();
        ConcurrentLinkedQueue<Long> alertLatencies = new ConcurrentLinkedQueue<>();
        Map<String, AtomicLong> alertsByRule = new java.util.concurrent.ConcurrentHashMap<>();

        RuleEngine engine = new RuleEngine(ComplianceConfig.buildRules());
        engine.addListener(new TradeListener() {
            @Override
            public void onTrade(Trade trade) {
                tradesSeen.incrementAndGet();
            }

            @Override
            public void onAlert(Alert alert) {
                alertLatencies.add(alert.latencyNanos());
                alertsByRule.computeIfAbsent(alert.ruleName(), k -> new AtomicLong()).incrementAndGet();
            }
        });
        engine.start();

        try (ComplianceServer server = new ComplianceServer(0, engine)) {
            server.start();
            int port = server.port();
            System.out.printf("ComplianceServer listening on 127.0.0.1:%d%n", port);
            System.out.printf("Streaming %d trades at ~%.0f/sec (%d traders, %d instruments)...%n",
                    numTrades, rate, ComplianceConfig.TRADERS.size(), ComplianceConfig.INSTRUMENTS.size());

            long tStart = System.nanoTime();
            TradeSimulator sim = new TradeSimulator("127.0.0.1", port, numTrades, 42, rate);
            sim.run();

            // Wait for the engine to fully drain the queue.
            while (engine.processedCount() < numTrades) {
                Thread.sleep(50);
            }
            long tEnd = System.nanoTime();
            engine.stop();

            double seconds = (tEnd - tStart) / 1e9;
            System.out.println();
            System.out.println("=== Compliance Monitor Benchmark ===");
            System.out.printf("Trades sent:        %d%n", numTrades);
            System.out.printf("Trades processed:   %d%n", engine.processedCount());
            System.out.printf("Wall time:          %.3f s  (%.0f trades/sec sustained)%n", seconds, numTrades / seconds);
            System.out.printf("Total alerts:       %d%n", engine.alertCount());
            System.out.println("Alerts by rule:");
            alertsByRule.forEach((rule, count) -> System.out.printf("  %-24s %d%n", rule, count.get()));

            long[] samples = alertLatencies.stream().mapToLong(Long::longValue).toArray();
            LatencyStats.Report report = LatencyStats.percentiles(samples);
            System.out.println();
            System.out.println("Alert latency (trade ingest -> rule violation detected):");
            LatencyStats.print("alert_latency", report);
            System.out.printf("p99 alert latency %.3fms  (target: under 100ms)%n", report.p99Ms());
        }
    }
}
