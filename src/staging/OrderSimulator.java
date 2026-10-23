package staging;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Builds one deterministic order stream mixing genuinely compliant flow
 * with deliberately seeded violations, one label per order so the
 * benchmark can report exactly how many of each the gate actually caught.
 *
 * Compliant orders are generated with a bounded random walk (tracked
 * per PM+instrument, clamped well inside the position limit, with qty and
 * price capped so notional stays well inside the notional cap, and never
 * touching a restricted symbol) so that -- unless the gate itself has a bug
 * -- none of them can trip any rule. That is what "zero false blocks" is
 * testing: not that the data was tuned to pass, but that a stream built to
 * be compliant by definition is recognized as compliant by the gate.
 */
public final class OrderSimulator {
    public record LabeledOrder(Order order, String label) {}

    private static final long COMPLIANT_WALK_BOUND = 1_500;
    private static final long COMPLIANT_MAX_QTY = 50;
    private static final double COMPLIANT_MIN_PRICE = 10.0;
    private static final double COMPLIANT_MAX_PRICE = 500.0;

    public static List<LabeledOrder> generate(long seed, int compliantCount, int restrictedViolations,
                                                int notionalViolations, int positionViolations) {
        Random rnd = new Random(seed);
        List<LabeledOrder> out = new ArrayList<>(compliantCount + restrictedViolations + notionalViolations + positionViolations);
        Map<String, Long> trackedPosition = new HashMap<>();

        int remainingCompliant = compliantCount;
        int remainingRestricted = restrictedViolations;
        int remainingNotional = notionalViolations;
        int remainingPosition = positionViolations;
        long orderId = 1;

        List<String> restrictedSymbols = List.copyOf(StagingConfig.RESTRICTED_INSTRUMENTS);

        while (remainingCompliant + remainingRestricted + remainingNotional + remainingPosition > 0) {
            int total = remainingCompliant + remainingRestricted + remainingNotional + remainingPosition;
            int pick = rnd.nextInt(total);

            if (pick < remainingCompliant) {
                String pm = StagingConfig.PMS.get(rnd.nextInt(StagingConfig.PMS.size()));
                String instrument = StagingConfig.CLEAN_INSTRUMENTS.get(rnd.nextInt(StagingConfig.CLEAN_INSTRUMENTS.size()));
                String key = pm + ":" + instrument;
                long current = trackedPosition.getOrDefault(key, 0L);

                char side;
                if (current > COMPLIANT_WALK_BOUND) side = 'S';
                else if (current < -COMPLIANT_WALK_BOUND) side = 'B';
                else side = rnd.nextBoolean() ? 'B' : 'S';

                long qty = 1 + rnd.nextInt((int) COMPLIANT_MAX_QTY);
                double price = COMPLIANT_MIN_PRICE + rnd.nextDouble() * (COMPLIANT_MAX_PRICE - COMPLIANT_MIN_PRICE);

                Order order = new Order(orderId++, pm, instrument, side, qty, Math.round(price * 100) / 100.0);
                trackedPosition.merge(key, order.signedQty(), Long::sum);
                out.add(new LabeledOrder(order, "COMPLIANT"));
                remainingCompliant--;
            } else if (pick < remainingCompliant + remainingRestricted) {
                String pm = StagingConfig.PMS.get(rnd.nextInt(StagingConfig.PMS.size()));
                String instrument = restrictedSymbols.get(rnd.nextInt(restrictedSymbols.size()));
                char side = rnd.nextBoolean() ? 'B' : 'S';
                long qty = 1 + rnd.nextInt(50);
                double price = 10.0 + rnd.nextDouble() * 490.0;
                Order order = new Order(orderId++, pm, instrument, side, qty, Math.round(price * 100) / 100.0);
                out.add(new LabeledOrder(order, "RESTRICTED"));
                remainingRestricted--;
            } else if (pick < remainingCompliant + remainingRestricted + remainingNotional) {
                String pm = StagingConfig.PMS.get(rnd.nextInt(StagingConfig.PMS.size()));
                String instrument = StagingConfig.CLEAN_INSTRUMENTS.get(rnd.nextInt(StagingConfig.CLEAN_INSTRUMENTS.size()));
                char side = rnd.nextBoolean() ? 'B' : 'S';
                long qty = 20 + rnd.nextInt(30); // 20-49 shares
                double price = 13_000.0 + rnd.nextDouble() * 4_000.0; // min notional 20*13,000=260,000 > 250,000 cap; qty too small to breach position
                Order order = new Order(orderId++, pm, instrument, side, qty, Math.round(price * 100) / 100.0);
                out.add(new LabeledOrder(order, "NOTIONAL"));
                remainingNotional--;
            } else {
                String pm = StagingConfig.PMS.get(rnd.nextInt(StagingConfig.PMS.size()));
                String instrument = StagingConfig.CLEAN_INSTRUMENTS.get(rnd.nextInt(StagingConfig.CLEAN_INSTRUMENTS.size()));
                char side = rnd.nextBoolean() ? 'B' : 'S';
                long qty = 60_000 + rnd.nextInt(5_000); // alone exceeds the 50,000 position limit
                double price = 1.0 + rnd.nextDouble() * 2.0; // kept low so notional (max ~195k) stays under the 250k cap
                Order order = new Order(orderId++, pm, instrument, side, qty, Math.round(price * 100) / 100.0);
                out.add(new LabeledOrder(order, "POSITION"));
                remainingPosition--;
            }
        }
        return out;
    }
}
