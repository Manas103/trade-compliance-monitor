package staging;

import java.util.List;
import java.util.Optional;

/**
 * The pre-trade gate itself: every proposed order is checked against every
 * rule, in order, before it is allowed to affect the PM's book. The first
 * rule to object blocks the order outright -- unlike compliance.RuleEngine
 * (which reports every rule that fires on an already-executed trade), a
 * staging gate's job is a single release/no-release decision, so only the
 * first reason is kept. Every order, accepted or blocked, gets exactly one
 * audit row.
 */
public final class OrderStagingGate {
    private final List<StagingRule> rules;
    private final PmBook book;
    private final AuditLog auditLog;

    public OrderStagingGate(List<StagingRule> rules, PmBook book, AuditLog auditLog) {
        this.rules = rules;
        this.book = book;
        this.auditLog = auditLog;
    }

    public Decision evaluate(Order order) {
        for (StagingRule rule : rules) {
            Optional<String> reason = rule.check(order, book);
            if (reason.isPresent()) {
                auditLog.append(order.orderId(), order.pmId(), order.instrument(), order.side(),
                        order.qty(), order.price(), Decision.BLOCKED,
                        rule.name() + ": " + reason.get(), book.currentPosition(order.pmId(), order.instrument()));
                return Decision.BLOCKED;
            }
        }
        long resultingPosition = book.apply(order);
        auditLog.append(order.orderId(), order.pmId(), order.instrument(), order.side(),
                order.qty(), order.price(), Decision.ACCEPTED, "", resultingPosition);
        return Decision.ACCEPTED;
    }
}
