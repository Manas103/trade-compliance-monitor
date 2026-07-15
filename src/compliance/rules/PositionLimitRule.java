package compliance.rules;

import compliance.Alert;
import compliance.Rule;
import compliance.Severity;
import compliance.Trade;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Tracks each trader's net signed position per instrument (buys add,
 * sells subtract) and flags a trade that pushes it past a configured
 * absolute limit.
 */
public final class PositionLimitRule implements Rule {
    private final long maxAbsPosition;
    private final Map<String, Long> positions = new HashMap<>();

    public PositionLimitRule(long maxAbsPosition) {
        this.maxAbsPosition = maxAbsPosition;
    }

    @Override
    public String name() {
        return "POSITION_LIMIT";
    }

    @Override
    public Optional<Alert> evaluate(Trade trade) {
        String key = trade.traderId() + ":" + trade.instrument();
        long delta = trade.side() == 'B' ? trade.qty() : -trade.qty();
        long newPosition = positions.merge(key, delta, Long::sum);

        if (Math.abs(newPosition) <= maxAbsPosition) return Optional.empty();

        String msg = String.format("Trader %s net position in %s now %,d (limit +/-%,d)",
                trade.traderId(), trade.instrument(), newPosition, maxAbsPosition);
        return Optional.of(new Alert(trade, name(), Severity.MEDIUM, msg, System.nanoTime()));
    }
}
