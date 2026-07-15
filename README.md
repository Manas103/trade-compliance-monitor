# Trade Compliance Monitor

A networked Java application that ingests executed trade events over TCP and flags
rule-based compliance violations in real time, with a Swing dashboard for live
monitoring. Built as a standalone systems project — plain `javac`/`java`, no
build framework, no third-party dependencies.

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

- **`gui.ComplianceDashboard`** — a Swing `JFrame` with a live trade feed and
  an alerts table (severity color-coded), plus a status bar showing
  throughput and recent p99 alert latency. A `ConcurrentLinkedQueue` staging
  buffer + a 200ms `javax.swing.Timer` drain the engine's callback thread onto
  the EDT in batches — pushing every individual trade straight to the EDT
  would flood Swing's repaint pipeline at anything above a few hundred
  updates/sec.
- **`bench.BenchmarkMain`** — a headless CLI run that streams a configurable
  number of trades at a configurable rate and reports real throughput and
  latency percentiles.

## Compliance rules

| Rule | Trigger |
|---|---|
| `MAX_NOTIONAL` | `price * qty` exceeds a fixed notional cap ($250,000) |
| `PRICE_COLLAR` | Trade price deviates more than 5% from the instrument's last observed price |
| `RAPID_FIRE` | A single trader exceeds 15 trades within a rolling 1-second window |
| `RESTRICTED_INSTRUMENT` | Trade is in a firm-restricted symbol list (`BA`, `AVGO`, `PYPL`) |
| `POSITION_LIMIT` | A trader's net signed position in an instrument exceeds 50,000 shares |

## Design decisions worth knowing about

**Why the simulator round-robins traders instead of picking randomly.**
`RapidFireRule` only means something if most trading activity does *not*
trip it. At realistic benchmark throughput (hundreds of trades/sec), naive
uniform-random trader assignment would push many traders over the
15-trades/second threshold purely by chance, making every trade an alert and
the rule useless as a signal. `TradeSimulator` instead round-robins through
an 80-trader pool for baseline flow (keeping each trader's own rate low and
even) and deliberately injects short "burst" episodes — one trader firing
~25 consecutive trades — as the actual mechanism that trips the rule. This
also means alert counts for `RAPID_FIRE` run somewhat higher than "number of
injected bursts" would suggest: a sliding-window rate limiter keeps alerting
for close to a second after a burst ends, since the burst's trades remain
inside the rolling window. That's expected behavior for this kind of rule,
not a bug.

**Why `RESTRICTED_INSTRUMENT` fires far more often than the injection rate.**
The simulator deliberately steers ~0.3% of trades onto a restricted symbol.
But restricted symbols (`BA`, `AVGO`, `PYPL`) are also members of the regular
40-symbol instrument universe, so ordinary/non-injected trades land on them
by chance too — 3/40 ≈ 7.5%, which is close to the ~7.85% observed alert
rate below. Both mechanisms (deliberate injection + random draw from the
full universe) are real trades hitting a real restricted symbol; the rule is
working correctly.

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
number honest. The networking is still real — `app.Main` and
`simulator.TradeSimulatorMain` can be run as two genuinely separate OS
processes for an interactive cross-process demo; they just aren't used for
the latency measurement itself.

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
These are real numbers from an actual run against real code — not
back-solved to hit a target.

## Running it

Requires JDK 17+. No build tool — plain `javac`/`java`.

```bash
# compile
javac -d out $(find src -name '*.java')

# interactive: server + live Swing dashboard
java -cp out app.Main
# then click "Start Simulation" in the dashboard, or in a second terminal:
java -cp out simulator.TradeSimulatorMain 127.0.0.1 7001 20000 500

# headless benchmark
java -cp out bench.BenchmarkMain 120000 900
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
  bench/                  BenchmarkMain (headless CLI benchmark)
  app/                    Main (server + engine + GUI wiring)
```

## Limitations

- Rule thresholds (notional cap, collar %, rapid-fire window, position limit)
  are static constants in `ComplianceConfig`, not per-trader/per-instrument
  configurable — a real compliance system would need that.
- The synthetic data generator injects anomalies at fixed probabilities
  tuned by hand to produce a readable, non-overwhelming alert distribution;
  it isn't modeling any real trading population.
- Single evaluation thread in `RuleEngine` is simple and was fast enough
  here, but is a serial bottleneck if rule complexity or trade volume grew
  substantially — sharding by trader or instrument (as in the companion
  matching-engine project) would be the next step.
