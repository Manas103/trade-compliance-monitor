package compliance;

/**
 * One executed trade. ingestTsNanos is stamped when the compliance server
 * reads the line off the socket (see ComplianceServer), not when the
 * simulator generated it -- that's the timestamp alert latency is measured
 * against, since it's the point this system actually became aware of the
 * trade.
 */
public record Trade(
        long tradeId,
        String traderId,
        String instrument,
        char side, // 'B' or 'S'
        double price,
        long qty,
        long ingestTsNanos
) {
    public double notional() {
        return price * qty;
    }
}
