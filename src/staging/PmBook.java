package staging;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Net signed position per (pmId, instrument), keyed separately per manager
 * so one PM's flow can never count against another's limit -- the whole
 * point of staging orders per-manager instead of firm-wide.
 */
public final class PmBook {
    private final Map<String, Long> positions = new ConcurrentHashMap<>();

    private static String key(String pmId, String instrument) {
        return pmId + ":" + instrument;
    }

    /** What the position would become if order were applied, without mutating state. */
    public long positionIfApplied(Order order) {
        return positions.getOrDefault(key(order.pmId(), order.instrument()), 0L) + order.signedQty();
    }

    public long currentPosition(String pmId, String instrument) {
        return positions.getOrDefault(key(pmId, instrument), 0L);
    }

    /** Commits the order's effect. Only called after a gate has accepted it. */
    public long apply(Order order) {
        return positions.merge(key(order.pmId(), order.instrument()), order.signedQty(), Long::sum);
    }

    /** Independent, from-scratch recomputation over the full accepted-order history, used as a reference oracle. */
    public static Map<String, Long> recomputeFromHistory(Iterable<AuditRow> acceptedRowsInOrder) {
        Map<String, Long> recomputed = new HashMap<>();
        for (AuditRow row : acceptedRowsInOrder) {
            if (row.decision() != Decision.ACCEPTED) continue;
            String k = key(row.pmId(), row.instrument());
            long delta = row.side() == 'B' ? row.qty() : -row.qty();
            recomputed.merge(k, delta, Long::sum);
        }
        return recomputed;
    }
}
