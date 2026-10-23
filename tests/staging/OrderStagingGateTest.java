package staging;

import java.util.List;
import java.util.Map;

/**
 * Hand-rolled check suite (no JUnit, matching this repo's no-build-tool
 * approach): each check prints PASS/FAIL and main() exits non-zero on any
 * failure, so it works as a CI gate the same way a JUnit run would.
 */
public final class OrderStagingGateTest {
    private static int failures = 0;

    public static void main(String[] args) {
        testRestrictedListBlocks();
        testNotionalLimitBlocks();
        testPositionLimitBlocks();
        testCompliantOrderAccepted();
        testAuditLogIsAppendOnly();
        testAuditRowWrittenForEveryOrder();
        testPositionOracleAgreesWithLiveBook();
        testFirstFailingRuleWins();
        testFullSimulatedStreamZeroFalseBlocks();

        if (failures > 0) {
            System.out.printf("%d check(s) FAILED%n", failures);
            System.exit(1);
        }
        System.out.println("All checks passed.");
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("PASS " + name);
        } else {
            System.out.println("FAIL " + name);
            failures++;
        }
    }

    private static OrderStagingGate freshGate(PmBook book, AuditLog log) {
        return new OrderStagingGate(StagingConfig.buildRules(), book, log);
    }

    private static void testRestrictedListBlocks() {
        PmBook book = new PmBook();
        AuditLog log = new AuditLog();
        OrderStagingGate gate = freshGate(book, log);
        Order order = new Order(1, "PM01", "BA", 'B', 10, 100.0);
        check("restricted instrument is blocked", gate.evaluate(order) == Decision.BLOCKED);
    }

    private static void testNotionalLimitBlocks() {
        PmBook book = new PmBook();
        AuditLog log = new AuditLog();
        OrderStagingGate gate = freshGate(book, log);
        Order order = new Order(1, "PM01", "AAPL", 'B', 30, 10_000.0); // notional 300,000 > 250,000
        check("over-notional order is blocked", gate.evaluate(order) == Decision.BLOCKED);
    }

    private static void testPositionLimitBlocks() {
        PmBook book = new PmBook();
        AuditLog log = new AuditLog();
        OrderStagingGate gate = freshGate(book, log);
        Order order = new Order(1, "PM01", "AAPL", 'B', 60_000, 1.0); // resultant position 60,000 > 50,000
        check("over-position order is blocked", gate.evaluate(order) == Decision.BLOCKED);
    }

    private static void testCompliantOrderAccepted() {
        PmBook book = new PmBook();
        AuditLog log = new AuditLog();
        OrderStagingGate gate = freshGate(book, log);
        Order order = new Order(1, "PM01", "AAPL", 'B', 10, 100.0);
        check("ordinary compliant order is accepted", gate.evaluate(order) == Decision.ACCEPTED);
    }

    private static void testAuditLogIsAppendOnly() {
        AuditLog log = new AuditLog();
        log.append(1, "PM01", "AAPL", 'B', 10, 100.0, Decision.ACCEPTED, "", 10);
        boolean hasRemoveMethod = false;
        for (var m : AuditLog.class.getDeclaredMethods()) {
            if (m.getName().toLowerCase().contains("remove") || m.getName().toLowerCase().contains("clear")
                    || m.getName().toLowerCase().contains("set")) {
                hasRemoveMethod = true;
            }
        }
        check("AuditLog exposes no remove/clear/set mutator", !hasRemoveMethod);
        check("rows() snapshot grows only by append", log.rows().size() == 1);
    }

    private static void testAuditRowWrittenForEveryOrder() {
        PmBook book = new PmBook();
        AuditLog log = new AuditLog();
        OrderStagingGate gate = freshGate(book, log);
        gate.evaluate(new Order(1, "PM01", "AAPL", 'B', 10, 100.0)); // accepted
        gate.evaluate(new Order(2, "PM01", "BA", 'B', 10, 100.0));   // blocked
        check("every evaluated order produces exactly one audit row", log.size() == 2);
    }

    private static void testPositionOracleAgreesWithLiveBook() {
        PmBook book = new PmBook();
        AuditLog log = new AuditLog();
        OrderStagingGate gate = freshGate(book, log);
        for (int i = 0; i < 50; i++) {
            char side = (i % 3 == 0) ? 'S' : 'B';
            gate.evaluate(new Order(i, "PM01", "AAPL", side, 5, 50.0));
        }
        Map<String, Long> oracle = PmBook.recomputeFromHistory(log.rows());
        long live = book.currentPosition("PM01", "AAPL");
        check("live PmBook position matches independent audit-log recomputation",
                oracle.get("PM01:AAPL") != null && oracle.get("PM01:AAPL") == live);
    }

    private static void testFirstFailingRuleWins() {
        PmBook book = new PmBook();
        AuditLog log = new AuditLog();
        OrderStagingGate gate = freshGate(book, log);
        // Restricted AND would also breach position if it were applied -- restricted must win since it is checked first.
        Order order = new Order(1, "PM01", "BA", 'B', 60_000, 1.0);
        gate.evaluate(order);
        List<AuditRow> rows = log.rows();
        check("restricted-list reason is reported ahead of position reason",
                rows.get(0).reason().startsWith("RESTRICTED_LIST"));
    }

    private static void testFullSimulatedStreamZeroFalseBlocks() {
        List<OrderSimulator.LabeledOrder> stream = OrderSimulator.generate(99L, 500, 20, 20, 20);
        PmBook book = new PmBook();
        AuditLog log = new AuditLog();
        OrderStagingGate gate = freshGate(book, log);
        int falseBlocks = 0;
        int seededCaught = 0;
        int seededTotal = 0;
        for (OrderSimulator.LabeledOrder lo : stream) {
            Decision d = gate.evaluate(lo.order());
            boolean seeded = !lo.label().equals("COMPLIANT");
            if (seeded) {
                seededTotal++;
                if (d == Decision.BLOCKED) seededCaught++;
            } else if (d == Decision.BLOCKED) {
                falseBlocks++;
            }
        }
        check("small simulated stream: zero false blocks on compliant orders", falseBlocks == 0);
        check("small simulated stream: every seeded violation caught", seededCaught == seededTotal);
    }
}
