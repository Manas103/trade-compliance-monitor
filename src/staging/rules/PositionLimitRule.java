package staging.rules;

import staging.Order;
import staging.PmBook;
import staging.StagingRule;

import java.util.Optional;

/** Blocks an order whose own resultant position (not just the order itself) would exceed the per-PM limit. */
public final class PositionLimitRule implements StagingRule {
    private final long maxAbsPosition;

    public PositionLimitRule(long maxAbsPosition) {
        this.maxAbsPosition = maxAbsPosition;
    }

    @Override
    public String name() {
        return "POSITION_LIMIT";
    }

    @Override
    public Optional<String> check(Order order, PmBook book) {
        long resultant = book.positionIfApplied(order);
        if (Math.abs(resultant) <= maxAbsPosition) return Optional.empty();
        return Optional.of(String.format("resultant position %,d in %s exceeds limit +/-%,d",
                resultant, order.instrument(), maxAbsPosition));
    }
}
