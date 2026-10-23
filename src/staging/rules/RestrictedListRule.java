package staging.rules;

import staging.Order;
import staging.PmBook;
import staging.StagingRule;

import java.util.Optional;
import java.util.Set;

public final class RestrictedListRule implements StagingRule {
    private final Set<String> restricted;

    public RestrictedListRule(Set<String> restricted) {
        this.restricted = restricted;
    }

    @Override
    public String name() {
        return "RESTRICTED_LIST";
    }

    @Override
    public Optional<String> check(Order order, PmBook book) {
        if (!restricted.contains(order.instrument())) return Optional.empty();
        return Optional.of(String.format("%s is on the firm restricted list", order.instrument()));
    }
}
