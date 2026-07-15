package compliance;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shared constants used both when wiring up RuleEngine's rules and when
 * generating synthetic order flow, so the simulator and the rules always
 * agree on which instruments exist and which are restricted.
 */
public final class ComplianceConfig {
    private ComplianceConfig() {}

    public static final List<String> INSTRUMENTS = List.of(
            "AAPL", "MSFT", "GOOG", "AMZN", "NVDA", "META", "TSLA", "JPM", "BAC", "GS",
            "XOM", "CVX", "UNH", "JNJ", "PFE", "V", "MA", "HD", "DIS", "NFLX",
            "KO", "PEP", "WMT", "COST", "MCD", "NKE", "INTC", "AMD", "CRM", "ORCL",
            "IBM", "CSCO", "QCOM", "TXN", "AVGO", "ADBE", "PYPL", "SBUX", "BA", "CAT");

    public static final Set<String> RESTRICTED_INSTRUMENTS = Set.of("BA", "AVGO", "PYPL");

    public static final List<String> TRADERS = buildTraders(80);

    private static List<String> buildTraders(int count) {
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) list.add(String.format("T%03d", i));
        return list;
    }

    public static final double MAX_NOTIONAL = 250_000.0;
    public static final double PRICE_COLLAR_FRACTION = 0.05;
    public static final long RAPID_FIRE_WINDOW_MS = 1000;
    public static final int RAPID_FIRE_MAX_TRADES = 15;
    public static final long POSITION_LIMIT = 50_000;

    public static java.util.List<Rule> buildRules() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new compliance.rules.MaxNotionalRule(MAX_NOTIONAL));
        rules.add(new compliance.rules.PriceCollarRule(PRICE_COLLAR_FRACTION));
        rules.add(new compliance.rules.RapidFireRule(RAPID_FIRE_WINDOW_MS, RAPID_FIRE_MAX_TRADES));
        rules.add(new compliance.rules.RestrictedInstrumentRule(RESTRICTED_INSTRUMENTS));
        rules.add(new compliance.rules.PositionLimitRule(POSITION_LIMIT));
        return rules;
    }
}
