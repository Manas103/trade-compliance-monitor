package compliance.rules;

import compliance.Alert;
import compliance.Rule;
import compliance.Severity;
import compliance.Trade;

import java.util.Optional;

/** Flags any single trade whose notional (price x qty) exceeds a threshold. */
public final class MaxNotionalRule implements Rule {
    private final double maxNotional;

    public MaxNotionalRule(double maxNotional) {
        this.maxNotional = maxNotional;
    }

    @Override
    public String name() {
        return "MAX_NOTIONAL";
    }

    @Override
    public Optional<Alert> evaluate(Trade trade) {
        double notional = trade.notional();
        if (notional <= maxNotional) return Optional.empty();
        Severity sev = notional > maxNotional * 3 ? Severity.HIGH : Severity.MEDIUM;
        String msg = String.format("Trade notional $%,.2f exceeds limit $%,.2f", notional, maxNotional);
        return Optional.of(new Alert(trade, name(), sev, msg, System.nanoTime()));
    }
}
