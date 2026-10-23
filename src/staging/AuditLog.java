package staging;

import java.util.ArrayList;
import java.util.List;

/**
 * Append-only by construction: the only mutator this class exposes is
 * append(); there is no remove, no update, and rows() hands back an
 * unmodifiable snapshot, so nothing downstream of this class -- including a
 * bug in StagingGate -- can rewrite history once a row has been written.
 */
public final class AuditLog {
    private final List<AuditRow> rows = new ArrayList<>();
    private long nextSeq = 1;

    public synchronized AuditRow append(long orderId, String pmId, String instrument, char side,
                                         long qty, double price, Decision decision, String reason,
                                         long resultingPosition) {
        AuditRow row = new AuditRow(nextSeq++, orderId, pmId, instrument, side, qty, price,
                decision, reason, resultingPosition, System.nanoTime());
        rows.add(row);
        return row;
    }

    public synchronized List<AuditRow> rows() {
        return List.copyOf(rows);
    }

    public synchronized int size() {
        return rows.size();
    }
}
