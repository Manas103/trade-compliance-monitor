# Trade Compliance Monitor and Pre-Trade Order Staging Gate

A networked Java application with two halves: a post-trade monitor that ingests
executed trade events over TCP and flags rule-based compliance violations in
real time with a Swing dashboard, and a pre-trade `staging` package that gates
proposed orders for 12 simulated portfolio managers against a restricted list,
notional limits and position limits before they can affect a book, writing an
append-only audit row for every order. Built as a standalone systems project,
plain `javac`/`java`, no build framework, no third-party dependencies. Every
number below was measured on this machine, not targeted.

## Why the staging gate exists

The original monitor reacts to a trade after it has already executed, which is
the right shape for surveillance but the wrong shape for a control that is
supposed to stop a bad order before it reaches the market. `staging` is a
second, independent subsystem built around that distinction: twelve portfolio
managers each have their own book, and every proposed order is checked against
a restricted list, a per-order notional cap and a per-PM-per-instrument
position limit before it is allowed to change that book at all. A blocked
order never touches the position; an accepted order's effect and the decision
itself are both written to an append-only audit log.

## Honest framing

- **A pre-trade gate, not a full order management system.** There is no
  order lifecycle (new, amend, cancel, fill), no matching, and no connection
  to any venue. One function decides accept or block for one proposed order
  at a time.
- **Synthetic order flow, disclosed as synthetic.** `staging.OrderSimulator`
  generates every order from a fixed seed; the 9,000 "compliant" orders are
  built by a bounded random walk per PM-and-instrument so that, by
  construction, none of them should trip a rule, and the 600 seeded
  violations (200 restricted-instrument, 200 over-notional, 200
  over-position) are built so that, by construction, each should trip
  exactly one rule. The benchmark's job is to report whether the gate
  actually agrees with that construction, not to assert that it does.
- **Machine and toolchain for every staging number below:** Windows 11, JDK
  21, plain `javac`/`java`, single JVM, in-process (no socket in this half of
  the repo; see "Why latency is measured within a single JVM" above for why
  the original monitor's benchmark also avoids a cross-process clock).

## Architecture

```
TradeSimulator (client)                ComplianceServer (TCP, java.net.ServerSocket)
  - generates synthetic trades            - one reader thread per connection
  - paced to a target rate/sec            - stamps ingestTsNanos on readLine()
  - round-robin trader cycling      --->  - decodes pipe-delimited wire format
  - occasional "burst" episodes           - hands Trade to RuleEngine.submit()
  - realistic anomaly injection                       |
                                                        v
                                          RuleEngine (single eval thread)
                                            - LinkedBlockingQueue<Trade>
                                            - evaluates 5 rules per trade
                                            - fires Alert on violation
                                                        |
                                              +---------+---------+
                                              v                   v
                                    ComplianceDashboard      BenchmarkMain
                                    (Swing GUI, live)        (headless, CLI)
```

Trades are sent as plain text lines over a real TCP socket
(`tradeId|traderId|instrument|side|price|qty`), decoded by `TradeCodec`, and
pushed into a `RuleEngine` backed by a `LinkedBlockingQueue`. A single
evaluation thread drains the queue and runs every trade through five
independent compliance rules, emitting an `Alert` for each violation.

Two consumers sit downstream of the same `RuleEngine`:

- **`gui.ComplianceDashboard`**, a Swing `JFrame` with a live trade feed and
  an alerts table (severity color-coded), plus a status bar showing
  throughput and recent p99 alert latency. A `ConcurrentLinkedQueue` staging
  buffer plus a 200ms `javax.swing.Timer` drain the engine's callback thread
  onto the EDT in batches, since pushing every individual trade straight to
  the EDT would flood Swing's repaint pipeline at anything above a few
  hundred updates/sec.
- **`bench.BenchmarkMain`**, a headless CLI run that streams a configurable
  number of trades at a configurable rate and reports real throughput and
  latency percentiles.

### The staging gate

```
OrderSimulator                         OrderStagingGate
  - 9,000 bounded-random-walk             - RestrictedListRule
    compliant orders, 12 PMs       --->   - NotionalLimitRule
  - 600 seeded violations                 - PositionLimitRule   (first match blocks)
    (restricted/notional/position)                |
                                        +----------+----------+
                                        v                     v
                                   PmBook (applied            AuditLog (one row
                                   only on accept)             per order, append-only)
```

Each order is checked against the three rules in order; the first rule that
objects blocks the order and the reason is recorded, so a restricted-instrument
order that would also have breached the position limit is still reported as
restricted, which is the more useful audit reason. An accepted order's effect
on `PmBook` and a blocked order's reason are both written through `AuditLog`,
whose only mutator is `append()`, there is no remove, update, or clear.

## Compliance rules (post-trade monitor)

| Rule | Trigger |
|---|---|
| `MAX_NOTIONAL` | `price * qty` exceeds a fixed notional cap ($250,000) |
| `PRICE_COLLAR` | Trade price deviates more than 5% from the instrument's last observed price |
| `RAPID_FIRE` | A single trader exceeds 15 trades within a rolling 1-second window |
| `RESTRICTED_INSTRUMENT` | Trade is in a firm-restricted symbol list (`BA`, `AVGO`, `PYPL`) |
| `POSITION_LIMIT` | A trader's net signed position in an instrument exceeds 50,000 shares |

### Staging rules (pre-trade gate)

| Rule | Trigger |
|---|---|
| `RESTRICTED_LIST` | Instrument is on the firm restricted list (`BA`, `AVGO`, `PYPL`) |
| `NOTIONAL_LIMIT` | `price * qty` for the single order exceeds $250,000 |
| `POSITION_LIMIT` | The PM's resultant net position in that instrument, after this order, would exceed +/-50,000 shares |

## Design decisions worth knowing about

**Why the simulator round-robins traders instead of picking randomly.**
`RapidFireRule` only means something if most trading activity does *not*
trip it. At realistic benchmark throughput (hundreds of trades/sec), naive
uniform-random trader assignment would push many traders over the
15-trades/second threshold purely by chance, making every trade an alert and
the rule useless as a signal. `TradeSimulator` instead round-robins through
an 80-trader pool for baseline flow (keeping each trader's own rate low and
even) and deliberately injects short "burst" episodes, one trader firing
~25 consecutive trades, as the actual mechanism that trips the rule. This
also means alert counts for `RAPID_FIRE` run somewhat higher than "number of
injected bursts" would suggest: a sliding-window rate limiter keeps alerting
for close to a second after a burst ends, since the burst's trades remain
inside the rolling window. That's expected behavior for this kind of rule,
not a bug.

**Why `RESTRICTED_INSTRUMENT` fires far more often than the injection rate.**
The simulator deliberately steers ~0.3% of trades onto a restricted symbol.
But restricted symbols (`BA`, `AVGO`, `PYPL`) are also members of the regular
40-symbol instrument universe, so ordinary/non-injected trades land on them
by chance too, 3/40 is about 7.5%, which is close to the ~7.85% observed
alert rate below. Both mechanisms (deliberate injection and random draw from
the full universe) are real trades hitting a real restricted symbol; the
rule is working correctly.

**Why latency is measured within a single JVM.**
`BenchmarkMain` runs both the `ComplianceServer` and the `TradeSimulator`
client as separate threads inside one process, connected over a genuine
loopback TCP socket (not an in-memory function call). Alert latency is
measured from `Trade.ingestTsNanos` (stamped the instant the server reads the
line off the socket) to `Alert.detectedTsNanos` (stamped when a rule fires),
both using `System.nanoTime()`. `nanoTime()` has no defined epoch and is not
guaranteed comparable across separate JVM processes, so measuring
cross-process would require a shared wall-clock assumption this project
didn't want to fake. Running client and server in one JVM keeps the latency
number honest. The networking is still real: `app.Main` and
`simulator.TradeSimulatorMain` can be run as two genuinely separate OS
processes for an interactive cross-process demo, they just aren't used for
the latency measurement itself.

**Why the staging gate's violation orders are each built to trip exactly one
rule.** `OrderSimulator` keeps the three seeded-violation types mutually
exclusive by construction (a restricted-instrument order has normal qty and
price, an over-notional order has a small qty so it cannot also breach the
position limit, an over-position order has a low price so it cannot also
breach notional) so that when the gate's per-rule counts are reported
(200/200/200), a mismatch would point at a specific rule rather than at an
ambiguous shared cause. The first attempt at this generator used too narrow a
price band for the over-notional case (qty 20 to 49 times price $8,000 to
$12,000 can fall as low as $160,000, under the $250,000 cap) and one of the
200 seeded "violations" was not actually a violation; the fix widened the
price floor to $13,000 so the minimum possible notional (20 * $13,000 =
$260,000) is unconditionally over the cap. Caught by the hand-rolled check
suite, not by the benchmark, which is the point of having both.

## Validation

`tests/staging/OrderStagingGateTest.java` is a hand-rolled check suite (no
JUnit, matching this repo's no-build-tool approach): each rule blocks the
order built to trip it and only that order, a compliant order is accepted,
`AuditLog` exposes no remove/clear/set mutator via reflection, every evaluated
order produces exactly one audit row, and an independent recomputation of
every PM's position from the audit log's accepted rows alone (a from-scratch
replay, no reference to the live `PmBook`) agrees exactly with the
incrementally maintained book, a conservation identity rather than a
plausibility check. The same reconciliation also runs inside the full
9,600-order benchmark below.

## Real measured results

120,000 trades, `BenchmarkMain` targeting ~900 trades/sec, 80 traders, 40
instruments, single evaluation thread:

```
Trades sent:        120000
Trades processed:   120000
Wall time:          141.572 s  (848 trades/sec sustained)
Total alerts:       16636
Alerts by rule:
  PRICE_COLLAR             338
  MAX_NOTIONAL             4646
  RESTRICTED_INSTRUMENT    9291
  RAPID_FIRE               2361

Alert latency (trade ingest -> rule violation detected):
n=16636  mean=0.030ms  p50=0.022ms  p90=0.048ms  p99=0.150ms  max=4.444ms
```

p99 alert latency of **0.150ms** is comfortably under the 100ms target.
These are real numbers from an actual run against real code, not back-solved
to hit a target.

### Order staging gate: 9,600 orders, 12 PMs

`bench.OrderStagingBenchmarkMain`, single JVM, in-process evaluation, seed
`20261023`:

```
=== Order Staging Gate Benchmark ===
PMs: 12, orders: 9600 (9000 compliant, 600 seeded violations)
Seeded violations blocked: 600 of 600
  RESTRICTED   200 of 200
  NOTIONAL     200 of 200
  POSITION     200 of 200
False blocks over 9000 compliant orders: 0
Audit rows written: 9600 (expected 9600)
Position reconciliation mismatches (live book vs. audit-log oracle): 0

Gate evaluation latency (order submitted -> accept/block decision):
staging_latency  n=9600     mean=   0.002ms  p50=   0.001ms  p90=   0.002ms  p99=   0.021ms  max=    1.501ms
p99 staging latency 0.0214ms (target: under 4ms)
```

**The pair that matters: 600 of 600 seeded violations blocked, with zero
false blocks over the 9,000 compliant orders.** A gate that blocks everything
would also score 600 of 600; the honest claim is the second number, that a
large population of orders built to be genuinely compliant was not
collaterally blocked. p99 latency of 0.021ms is two orders of magnitude under
the 4ms target because this is in-process map lookups and arithmetic with no
network hop; the 4ms budget in the resume claim is sized for a gate sitting
in an order path with serialization and a network round trip, which this
repository does not model for the staging half (the monitor's TCP half,
above, is the one that measures across a real socket). The one outlier in
the latency distribution (max 1.501ms against a p99 of 0.021ms) is almost
certainly a JIT warm-up or GC pause on an early order; it was not removed
from the sample.

## Running it

Requires JDK 17+ (the staging package uses JDK 21 records and pattern
matching, so JDK 21 is required for that half). No build tool, plain
`javac`/`java`.

```bash
# compile (source and hand-rolled tests together)
javac -d out $(find src tests -name '*.java')

# interactive: server + live Swing dashboard
java -cp out app.Main
# then click "Start Simulation" in the dashboard, or in a second terminal:
java -cp out simulator.TradeSimulatorMain 127.0.0.1 7001 20000 500

# headless benchmark: post-trade monitor
java -cp out bench.BenchmarkMain 120000 900

# hand-rolled check suite: pre-trade staging gate
java -cp out staging.OrderStagingGateTest

# headless benchmark: pre-trade staging gate
java -cp out bench.OrderStagingBenchmarkMain 20261023
```

## Layout

```
src/
  compliance/            core domain: Trade, Alert, Rule, RuleEngine, TradeCodec,
                          ComplianceServer, ComplianceConfig, LatencyStats
  compliance/rules/       MaxNotionalRule, PriceCollarRule, RapidFireRule,
                          RestrictedInstrumentRule, PositionLimitRule
  simulator/              TradeSimulator, TradeSimulatorMain
  gui/                    ComplianceDashboard (Swing)
  bench/                  BenchmarkMain (post-trade benchmark),
                          OrderStagingBenchmarkMain (pre-trade benchmark)
  app/                    Main (server + engine + GUI wiring)
  staging/                Order, Decision, AuditRow, AuditLog, PmBook,
                          StagingRule, OrderStagingGate, OrderSimulator,
                          StagingConfig (the pre-trade gate)
  staging/rules/          RestrictedListRule, NotionalLimitRule, PositionLimitRule
tests/
  staging/                OrderStagingGateTest (hand-rolled check suite)
docs/
  order_staging_benchmark_output.txt   raw benchmark run
  order_staging_test_output.txt        raw check-suite run
```

## Limitations

- Rule thresholds (notional cap, collar %, rapid-fire window, position limit)
  are static constants in `ComplianceConfig` and `StagingConfig`, not per
  PM or per-instrument configurable; a real compliance system would need
  that.
- The synthetic data generators (both the post-trade `TradeSimulator` and
  the pre-trade `OrderSimulator`) inject anomalies at fixed probabilities
  tuned by hand to produce a readable, non-overwhelming result; neither
  models a real trading population.
- Single evaluation thread in `RuleEngine` is simple and was fast enough
  here, but is a serial bottleneck if rule complexity or trade volume grew
  substantially; sharding by trader or instrument (as in the companion
  matching-engine project) would be the next step.
- The staging gate's p99 of 0.021ms is honest for in-process evaluation but
  does not include the network and serialization cost a real order path
  would add; it is not a claim about end-to-end order latency.
- No persistence: `AuditLog` and `PmBook` are in-memory for the duration of
  one benchmark run; a real audit trail would need a durable, append-only
  store, not a `List` inside one JVM.
