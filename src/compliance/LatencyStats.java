package compliance;

import java.util.Arrays;

public final class LatencyStats {
    private LatencyStats() {}

    public record Report(long n, double meanMs, double p50Ms, double p90Ms, double p99Ms, double maxMs) {}

    public static Report percentiles(long[] samplesNanos) {
        if (samplesNanos.length == 0) return new Report(0, 0, 0, 0, 0, 0);
        long[] sorted = samplesNanos.clone();
        Arrays.sort(sorted);
        double sum = 0;
        for (long v : sorted) sum += v;
        double mean = sum / sorted.length;
        return new Report(
                sorted.length,
                mean / 1_000_000.0,
                at(sorted, 0.50) / 1_000_000.0,
                at(sorted, 0.90) / 1_000_000.0,
                at(sorted, 0.99) / 1_000_000.0,
                sorted[sorted.length - 1] / 1_000_000.0
        );
    }

    private static long at(long[] sorted, double p) {
        int idx = (int) (p * (sorted.length - 1));
        return sorted[idx];
    }

    public static void print(String label, Report r) {
        System.out.printf("%-16s n=%-8d mean=%8.3fms  p50=%8.3fms  p90=%8.3fms  p99=%8.3fms  max=%9.3fms%n",
                label, r.n(), r.meanMs(), r.p50Ms(), r.p90Ms(), r.p99Ms(), r.maxMs());
    }
}
