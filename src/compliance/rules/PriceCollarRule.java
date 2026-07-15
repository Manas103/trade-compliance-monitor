package compliance.rules;

import compliance.Alert;
import compliance.Rule;
import compliance.Severity;
import compliance.Trade;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Flags a trade whose price deviates more than a threshold fraction from
 * the last-seen price for that instrument -- a classic "fat finger" /
 * erroneous-price check. Maintains one rolling last-price per instrument;
 * safe without synchronization because RuleEngine only ever calls rules
 * from its single evaluation thread.
 */
public final class PriceCollarRule implements Rule {
    private final double maxDeviationFraction;
    private final Map<String, Double> lastPrice = new HashMap<>();

    public PriceCollarRule(double maxDeviationFraction) {
        this.maxDeviationFraction = maxDeviationFraction;
    }

    @Override
    public String name() {
        return "PRICE_COLLAR";
    }

    @Override
    public Optional<Alert> evaluate(Trade trade) {
        Double prev = lastPrice.get(trade.instrument());
        lastPrice.put(trade.instrument(), trade.price());
        if (prev == null) return Optional.empty();

        double deviation = Math.abs(trade.price() - prev) / prev;
        if (deviation <= maxDeviationFraction) return Optional.empty();

        Severity sev = deviation > maxDeviationFraction * 2 ? Severity.HIGH : Severity.MEDIUM;
        String msg = String.format("Price %.2f deviates %.1f%% from last trade price %.2f on %s",
                trade.price(), deviation * 100, prev, trade.instrument());
        return Optional.of(new Alert(trade, name(), sev, msg, System.nanoTime()));
    }
}
