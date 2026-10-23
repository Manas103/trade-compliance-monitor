package staging;

import compliance.ComplianceConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class StagingConfig {
    private StagingConfig() {}

    public static final int PM_COUNT = 12;
    public static final List<String> PMS = buildPms(PM_COUNT);

    public static final Set<String> RESTRICTED_INSTRUMENTS = ComplianceConfig.RESTRICTED_INSTRUMENTS;

    /** The 37 non-restricted symbols from the shared instrument universe; compliant flow never touches the restricted 3. */
    public static final List<String> CLEAN_INSTRUMENTS = ComplianceConfig.INSTRUMENTS.stream()
            .filter(sym -> !RESTRICTED_INSTRUMENTS.contains(sym))
            .toList();

    public static final double MAX_NOTIONAL = 250_000.0;
    public static final long POSITION_LIMIT = 50_000;

    private static List<String> buildPms(int count) {
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) list.add(String.format("PM%02d", i));
        return list;
    }

    public static List<StagingRule> buildRules() {
        List<StagingRule> rules = new ArrayList<>();
        rules.add(new staging.rules.RestrictedListRule(RESTRICTED_INSTRUMENTS));
        rules.add(new staging.rules.NotionalLimitRule(MAX_NOTIONAL));
        rules.add(new staging.rules.PositionLimitRule(POSITION_LIMIT));
        return rules;
    }
}
