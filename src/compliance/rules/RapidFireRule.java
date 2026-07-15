package compliance.rules;

import compliance.Alert;
import compliance.Rule;
import compliance.Severity;
import compliance.Trade;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Flags a trader who submits more than {@code maxTradesPerWindow} trades
 * within a rolling {@code windowMillis} window -- a proxy for algo
 * malfunction or potential spoofing/layering-style burst activity. Uses
 * trade ingest time (not wall clock at evaluation time) as the rolling
 * window's clock, so replaying a recorded trade log still detects bursts
 * correctly.
 */
public final class RapidFireRule implements Rule {
    private final long windowNanos;
    private final int maxTradesPerWindow;
    private final Map<String, Deque<Long>> recentTimestamps = new HashMap<>();

    public RapidFireRule(long windowMillis, int maxTradesPerWindow) {
        this.windowNanos = windowMillis * 1_000_000L;
        this.maxTradesPerWindow = maxTradesPerWindow;
    }

    @Override
    public String name() {
        return "RAPID_FIRE";
    }

    @Override
    public Optional<Alert> evaluate(Trade trade) {
        Deque<Long> times = recentTimestamps.computeIfAbsent(trade.traderId(), k -> new ArrayDeque<>());
        long now = trade.ingestTsNanos();
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > windowNanos) {
            times.pollFirst();
        }
        if (times.size() <= maxTradesPerWindow) return Optional.empty();

        String msg = String.format("Trader %s submitted %d trades in the last %dms (limit %d)",
                trade.traderId(), times.size(), windowNanos / 1_000_000L, maxTradesPerWindow);
        return Optional.of(new Alert(trade, name(), Severity.HIGH, msg, System.nanoTime()));
    }
}
