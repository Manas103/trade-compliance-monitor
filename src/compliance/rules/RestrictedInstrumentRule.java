package compliance.rules;

import compliance.Alert;
import compliance.Rule;
import compliance.Severity;
import compliance.Trade;

import java.util.Optional;
import java.util.Set;

/** Flags any trade in a statically configured restricted-instrument list. */
public final class RestrictedInstrumentRule implements Rule {
    private final Set<String> restricted;

    public RestrictedInstrumentRule(Set<String> restricted) {
        this.restricted = restricted;
    }

    @Override
    public String name() {
        return "RESTRICTED_INSTRUMENT";
    }

    @Override
    public Optional<Alert> evaluate(Trade trade) {
        if (!restricted.contains(trade.instrument())) return Optional.empty();
        String msg = "Trade on restricted instrument " + trade.instrument();
        return Optional.of(new Alert(trade, name(), Severity.HIGH, msg, System.nanoTime()));
    }
}
