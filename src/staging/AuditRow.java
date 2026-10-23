package staging;

/**
 * One immutable audit entry. seq is assigned by AuditLog at append time and
 * is strictly increasing, so the log can be replayed in order and a gap in
 * seq would itself prove a missing or reordered row.
 */
public record AuditRow(
        long seq,
        long orderId,
        String pmId,
        String instrument,
        char side,
        long qty,
        double price,
        Decision decision,
        String reason,
        long resultingPosition,
        long tsNanos
) {
}
