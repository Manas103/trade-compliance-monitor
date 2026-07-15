package compliance;

/**
 * Simple newline-delimited, pipe-separated wire format:
 * {@code tradeId|traderId|instrument|side|price|qty}
 *
 * No timestamp field -- ingestTsNanos is stamped by the server the instant
 * it reads the line, not carried over the wire (see ComplianceServer),
 * since that's the moment this system takes ownership of the trade.
 */
public final class TradeCodec {

    private TradeCodec() {}

    public record Fields(long tradeId, String traderId, String instrument, char side, double price, long qty) {}

    public static String encode(long tradeId, String traderId, String instrument, char side, double price, long qty) {
        return tradeId + "|" + traderId + "|" + instrument + "|" + side + "|" + price + "|" + qty;
    }

    public static Fields decode(String line) {
        String[] parts = line.split("\\|");
        if (parts.length != 6) {
            throw new IllegalArgumentException("Malformed trade line: " + line);
        }
        return new Fields(
                Long.parseLong(parts[0]),
                parts[1],
                parts[2],
                parts[3].charAt(0),
                Double.parseDouble(parts[4]),
                Long.parseLong(parts[5])
        );
    }
}
