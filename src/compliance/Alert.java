package compliance;

public record Alert(
        Trade trade,
        String ruleName,
        Severity severity,
        String message,
        long detectedTsNanos
) {
    public long latencyNanos() {
        return detectedTsNanos - trade.ingestTsNanos();
    }
}
