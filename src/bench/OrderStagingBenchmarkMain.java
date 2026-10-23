package bench;

import compliance.LatencyStats;
import staging.AuditLog;
import staging.AuditRow;
import staging.Decision;
import staging.OrderSimulator;
import staging.OrderStagingGate;
import staging.PmBook;
import staging.StagingConfig;

import java.util.List;
import java.util.Map;

/**
 * Headless end-to-end measurement of the pre-trade order staging gate:
 * 9,000 orders built to be genuinely compliant, 600 built to be genuine
 * seeded violations (restricted-list, notional, and position, 200 each),
 * fed through one OrderStagingGate in a single JVM. Evaluation is
 * synchronous and in-process -- there is no network hop in the order path
 * here, unlike BenchmarkMain's TCP-based monitor -- so latency is measured
 * directly around each gate.evaluate() call with System.nanoTime().
 *
 * Usage: java bench.OrderStagingBenchmarkMain [seed]
 */
public final class OrderStagingBenchmarkMain {
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20261023L;

        List<OrderSimulator.LabeledOrder> stream = OrderSimulator.generate(seed, 9_000, 200, 200, 200);

        PmBook book = new PmBook();
        AuditLog auditLog = new AuditLog();
        OrderStagingGate gate = new OrderStagingGate(StagingConfig.buildRules(), book, auditLog);

        long[] latenciesNanos = new long[stream.size()];
        int falseBlocks = 0;
        int seededCaught = 0;
        int seededTotal = 0;
        Map<String, Integer> caughtByType = new java.util.LinkedHashMap<>();
        caughtByType.put("RESTRICTED", 0);
        caughtByType.put("NOTIONAL", 0);
        caughtByType.put("POSITION", 0);

        for (int i = 0; i < stream.size(); i++) {
            OrderSimulator.LabeledOrder labeled = stream.get(i);
            long t0 = System.nanoTime();
            Decision decision = gate.evaluate(labeled.order());
            long t1 = System.nanoTime();
            latenciesNanos[i] = t1 - t0;

            boolean isSeeded = !labeled.label().equals("COMPLIANT");
            if (isSeeded) {
                seededTotal++;
                if (decision == Decision.BLOCKED) {
                    seededCaught++;
                    caughtByType.merge(labeled.label(), 1, Integer::sum);
                }
            } else if (decision == Decision.BLOCKED) {
                falseBlocks++;
            }
        }

        // Invariant check: recompute every PM's position from the audit log's
        // accepted rows alone and diff it against the incrementally maintained
        // PmBook -- a conservation identity, not just "it produced output".
        Map<String, Long> recomputed = PmBook.recomputeFromHistory(auditLog.rows());
        int mismatches = 0;
        for (AuditRow row : auditLog.rows()) {
            if (row.decision() != Decision.ACCEPTED) continue;
            String key = row.pmId() + ":" + row.instrument();
            if (!recomputed.containsKey(key)) continue;
            long live = book.currentPosition(row.pmId(), row.instrument());
            long oracle = recomputed.get(key);
            if (live != oracle) mismatches++;
        }

        int compliantCount = stream.size() - seededTotal;
        LatencyStats.Report report = LatencyStats.percentiles(latenciesNanos);

        System.out.println("=== Order Staging Gate Benchmark ===");
        System.out.printf("PMs: %d, orders: %d (%d compliant, %d seeded violations)%n",
                StagingConfig.PMS.size(), stream.size(), compliantCount, seededTotal);
        System.out.printf("Seeded violations blocked: %d of %d%n", seededCaught, seededTotal);
        caughtByType.forEach((type, count) -> System.out.printf("  %-12s %d of 200%n", type, count));
        System.out.printf("False blocks over %d compliant orders: %d%n", compliantCount, falseBlocks);
        System.out.printf("Audit rows written: %d (expected %d)%n", auditLog.size(), stream.size());
        System.out.printf("Position reconciliation mismatches (live book vs. audit-log oracle): %d%n", mismatches);
        System.out.println();
        System.out.println("Gate evaluation latency (order submitted -> accept/block decision):");
        LatencyStats.print("staging_latency", report);
        System.out.printf("p99 staging latency %.4fms (target: under 4ms)%n", report.p99Ms());
    }
}
