package compliance;

import java.util.Optional;

/**
 * A single compliance rule evaluated against one trade at a time, in the
 * order trades arrive. Rules are stateful (rolling per-trader/per-instrument
 * history) but are only ever called from RuleEngine's single evaluation
 * thread, so implementations don't need any internal synchronization.
 */
public interface Rule {
    String name();
    Optional<Alert> evaluate(Trade trade);
}
