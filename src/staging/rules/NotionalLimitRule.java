package staging.rules;

import staging.Order;
import staging.PmBook;
import staging.StagingRule;

import java.util.Optional;

/** Caps the notional of a single order, independent of any running position. */
public final class NotionalLimitRule implements StagingRule {
    private final double maxNotional;

    public NotionalLimitRule(double maxNotional) {
        this.maxNotional = maxNotional;
    }

    @Override
    public String name() {
        return "NOTIONAL_LIMIT";
    }

    @Override
    public Optional<String> check(Order order, PmBook book) {
        double notional = order.notional();
        if (notional <= maxNotional) return Optional.empty();
        return Optional.of(String.format("order notional %.2f exceeds limit %.2f", notional, maxNotional));
    }
}
