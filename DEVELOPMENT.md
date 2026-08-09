# xit-mc — Development Documentation

> Status snapshot as of 2026-08-08. This document describes what has actually been implemented in the codebase so far — not the target design (see PRODUCT_REQUIREMENTS.md for that). Sections are marked ✅ Implemented, 🟡 Partial (stub/scaffolded), or ⬜ Not started.

## 1. What this project is

`xit-mc` (Maven artifact `com.kbquants:xit-mc`) is a **trade exit management system**: it accepts a trade invocation (a confirmed broker fill, or a manual `/track` command via Telegram), watches the live price, and manages the exit lifecycle — stop-loss, phase transitions, and profit-ownership locking — via a deterministic rules engine. It does not make entry decisions; see PRODUCT_REQUIREMENTS.md §2 for the full scope boundary.

As of this update the project runs **paper trades on live Upstox prices**. The Telegram bot accepts `/track`, `/exit`, `/status`, `/refresh`, `/ladder`, `/pause`, `/resume`, `/release`, `/observe` and `/manage`. With `MARKET_DATA=live` it resolves trading symbols against Upstox's instrument master, defaults the entry price to the LTP, sizes the position in whole lots from capital and risk, and measures every threshold net of broker-quoted costs.

**It never places a buy order.** It is purely an exit engine: every position it manages was bought elsewhere and handed to it. The order side (real sell orders, `UpstoxOrderFillFeed`) is Phase 3 — see IMPLEMENTATION_PLAN.md.

**Build:** Maven, Java 17, Lombok, Logback (JSON/structured logging via `logstash-logback-encoder`), Apache POI (declared, not yet used), the official Upstox Java SDK (`com.upstox.api:upstox-java-sdk:1.27`), Gson (pinned explicitly, used to parse Telegram's `getUpdates` response), JUnit 5. Telegram outbound messages use only the JDK's built-in `java.net.http.HttpClient`. `maven-shade-plugin` builds a runnable fat jar with `com.kbquants.Main` as the entry point.
**Current state:** `mvn test` → **243/243 tests passing**, `BUILD SUCCESS`. `mvn package` produces a runnable jar.

## 2. Package layout

```
com.kbquants
├── Main           ✅  Entry point: paper trading, with simulated or live Upstox market data
├── config         🟡  Configuration loading (all classes are empty stubs)
├── domain         ✅  Core value/state objects (Phase, TradeContext, MilestoneLadder + named sets,
│                      ActiveLadder, MonitorMode, enums)
├── engine         ✅  The exit/stop-loss/ownership rules engine, driven by MilestoneLadder
├── instrument     ✅  Instrument master (download, weekly cache, symbol resolution) + lot- and
│                      risk-aware position sizing + /track resolution
├── live           ✅  Upstox implementations (market data, quotes, charges, auth) + TradeMonitor
├── notification   ✅  Telegram bot: outbound alerts incl. inline keyboards + inbound commands
├── session        ✅  Broker-agnostic interfaces (MarketDataFeed, OrderFillFeed, QuoteService,
│                      ChargesService, BuyRequestResolver) + simulated/paper implementations
└── simulation     ✅  Synthetic price generation, batch execution ("runner"), and reporting
    ├── report     ✅  Console / CSV / JSON formatting of results
    └── runner     ✅  Orchestration of Exit×Ownership combinations over a price path
```

## 3. Domain model (`com.kbquants.domain`) — ✅ Implemented

| Type | Purpose |
|---|---|
| `TradeContext` | The single mutable state object for one trade: id, entry price, base price, quantity, current phase, current stop-loss, ATR, ownership %, hybrid-update count, `forceExited`/`isClosed` flags. Everything else in the engine reads/writes this object. |
| `Phase` (enum) | Trade lifecycle stage: `PHASE_1` (initial risk validation) → `PHASE_2` (base capital protection) → `PHASE_3` (profit protection) → `PHASE_4` (forced EOD exit, defined but not yet wired into transitions). |
| `ExitModel` (enum) | Exit "personality": `CONSERVATIVE`, `MODERATE`, `AGGRESSIVE`. Currently just a tag threaded through the engine/metrics — no model-specific behavior branches on it yet (see §7 gaps). |
| `OwnershipMode` (enum) | `CONTINUOUS` or `MILESTONE` — selects which `OwnershipStrategy` implementation is used. |
| `Milestone` | Immutable single rung: a **net-profit** `percent` (measured above `basePrice`, the cost-inclusive breakeven), an optional `phaseTransition` it triggers, and an optional `ownershipLockPercent` it locks. |
| `MilestoneLadder` | The single source of truth for every threshold in the system, **including the hard stop** — which lives here rather than as a constant because 20% below entry is a disaster stop on an equity and a routine wiggle on an option premium. Two named sets: `EQUITY` `[1, 2, 3, 5, 8, 13, 21, 34, 55]%` with PHASE_2 at 2%, PHASE_3 at 5% and a 20% hard stop; `OPTIONS` `[1, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233]%` with PHASE_2 at 8%, PHASE_3 at 21% and a 40% hard stop. `PhaseManager`, `MilestoneOwnershipStrategy`, `ProfitMilestoneTracker` and `StopLossEngine` all read the same instance. |
| `MilestoneSets` | The registry of selectable sets. Which one is active is a runtime choice (`/ladder`); what they contain is a code change, since the numbers encode trading intent worth reviewing in a diff. |
| `ActiveLadder` | Volatile holder for the selected set, shared by `TradeMonitor` and `PositionSizer` so the hard stop used for sizing cannot drift from the one the engine enforces. |
| `MonitorMode` (enum) | How far the engine may act on a trade: `MANAGED` (auto-exit), `OBSERVED` (report only, never exit), `RELEASED` (detached). Distinct from exiting — a released position stays open. |
| `Candle5m` | Immutable OHLC + start/end timestamp for a completed 5-minute candle. Defined for future phase-transition/structured evaluation use; not yet consumed anywhere. |
| `MarketSnapshot` | Immutable `(ltp, timestamp)` tick snapshot, intended for future tick-level/hybrid processing; not yet consumed. |

## 4. Exit engine (`com.kbquants.engine`) — ✅ Implemented (core rules)

This is the heart of what's been built. It's a small, deterministic rules pipeline driven by `ExitEngine.onPriceUpdate(price, context)`, called once per price tick:

Everything below measures from **`basePrice`, the cost-inclusive breakeven**, not the entry price. That is the whole difference between reporting gross and net: on a small position round-trip costs can exceed 1.5% of capital, enough for a gross gain to be a real loss.

1. **Hard safety stop** (`StopLossEngine.applyHardSafety`) — floor stop-loss at entry price − the active set's hard stop (20% EQUITY, 40% OPTIONS), every tick, regardless of phase.
2. **Phase transition** (`PhaseManager.evaluatePhaseTransition`) — trigger percentages come from `MilestoneLadder`, and **every** transition the price qualifies for is applied in the same tick, so a gap through both thresholds does not leave the trade a phase behind:
   - `PHASE_1 → PHASE_2` at basePrice × 1.02 (EQUITY) or × 1.08 (OPTIONS)
   - `PHASE_2 → PHASE_3` at basePrice × 1.05 (EQUITY) or × 1.21 (OPTIONS)
   - `PHASE_3`/`PHASE_4` currently have no further automatic transition logic.
3. **Base protection** (`StopLossEngine.applyBaseProtectionIfEligible`) — from `PHASE_2` **onward**, ratchets the stop-loss up to `basePrice`, so reaching PHASE_2 genuinely means capital is safe rather than losing exactly the costs.
4. **Ownership strategy** (`OwnershipStrategy.apply`, only active in `PHASE_3`) — locks a portion of **net** open profit as the new stop-loss, via one of two pluggable strategies (factory-selected by `OwnershipStrategyFactory`):
   - **`ContinuousOwnershipStrategy`** — locks a flat **30%** of open profit continuously once in Phase 3 (intentionally not milestone-based).
   - **`MilestoneOwnershipStrategy`** — locks an increasing share at milestones read from `MilestoneLadder.ownershipLockMap()`. The first lock always coincides with the PHASE_3 rung; a gap between them would be a dead zone where the stop sits at breakeven while profit runs:
     | Net profit (EQUITY / OPTIONS) | Ownership locked |
     |---|---|
     | ≥ 5% / 21% | 30% |
     | ≥ 8% / 34% | 50% |
     | ≥ 13% / 55% | 65% |
     | ≥ 21% / 89% | 75% |
     | ≥ 34% / 144% | 85% |
     | ≥ 55% / 233% | 90% |

All stop-loss writes go through `StopLossEngine.updateStopLoss`, which enforces a **monotonic ratchet** — a candidate SL is only applied if it's higher than the current one, so the stop-loss never moves against the trade.

`ExitEngine` also exposes `forceExit(context, price)` for an explicit forced close (sets SL to current price, marks `forceExited` and `isClosed`), and short-circuits all further processing once `context.isClosed()` is true. `ExitEngine`'s constructor now optionally accepts a `MilestoneLadder`; the no-arg convenience constructor uses `MilestoneLadder.defaultLadder()`.

**Test coverage:** `PhaseManagerTest`, `StopLossEngineTest`, `ContinuousOwnershipStrategyTest`, `MilestoneOwnershipStrategyTest`, `ExitEngineTest` (5–6 tests each) cover threshold boundaries (updated for the 5% Phase 2 trigger), ratchet-never-decreases behavior, phase gating, and end-to-end sequencing through `ExitEngine`. New: `MilestoneLadderTest` (9 tests) covers default/custom ladders, phase trigger lookup, ownership lock map derivation, and validation.

## 5. Session layer (`com.kbquants.session`) — ✅ Implemented (broker-agnostic interfaces)

This package holds every interface a broker or data source must implement to plug into the system, plus the non-broker-specific implementations. No class in this package imports anything Upstox-specific — that's the whole point.

- `TradingSession` — holds `TradingMode` + `TradeContext`, forwards each `onPrice(price, timestamp)` tick into `ExitEngine.onPriceUpdate`, and stops forwarding once the trade is closed.
- `TradingMode` (enum) — `SIMULATION`, `HISTORICAL`, `PAPER`, `LIVE`.
- `MarketDataFeed` / `PriceListener` — "where prices come from" / "what consumes them."
- `OrderFillFeed` / `TradeFillListener` / `TradeFillEvent` — **new, moved from `com.kbquants.live`.** `OrderFillFeed` is the broker-agnostic "where trade invocations come from" interface (`start(TradeFillListener)`, `stop()`). `TradeFillEvent` (orderId, instrumentKey, averagePrice, filledQuantity) and `TradeFillListener` were previously Upstox-adjacent classes living in the `live` package; they're genuinely broker-agnostic contracts and now live here, alongside `MarketDataFeed`. `UpstoxOrderFillFeed` (see §5.1) is the only real-broker implementation so far.
- `DeterministicSimulationFeed` — feeds a fixed, pre-built `List<Double>` of prices sequentially with synthetic incrementing timestamps. Used for deterministic, reproducible tests of the engine end-to-end.
- **`SimulatedMarketDataFeed`** — **new.** A `MarketDataFeed` implementation for paper trading: on `start()`, a background scheduled thread generates a new price every tick interval via a Gaussian random walk around the current price (`price += N(0,1) * volatility% * price`), floored above zero, and delivers it to the listener. Used by `Main` to drive paper trading without any broker.
- **`NoOpOrderFillFeed`** — **new.** An `OrderFillFeed` that never produces fills; used in paper mode where trades are invoked directly via Telegram's `/track` command rather than detected from a broker feed.

**Test coverage:** `TradingSessionTest` (3), `DeterministicSimulationFeedTest` (1), `SimulatedMarketDataFeedTest` (3 — deterministic-random tick generation, price floor under extreme downward moves, input validation).

### 5.1 Upstox broker implementation (`com.kbquants.live`) — ✅ Implemented

This package now holds only Upstox-specific code — broker-agnostic contracts moved to `session` (§5). It integrates with **Upstox** via the official `com.upstox.api:upstox-java-sdk:1.27` Maven dependency.

- **`UpstoxCredentials`** — immutable holder for `apiKey`, `apiSecret`, `redirectUri`, `accessToken`, `sandbox`. `UpstoxCredentials.fromEnv()` reads `UPSTOX_API_KEY`, `UPSTOX_API_SECRET`, `UPSTOX_REDIRECT_URI` (required), `UPSTOX_ACCESS_TOKEN` (optional), and `UPSTOX_SANDBOX` (optional, default `false`).
- **`UpstoxAuthService`** — implements Upstox's OAuth2 authorization-code flow (`buildAuthorizationUrl`, `exchangeCodeForToken`). Inherently manual/interactive (password + 2FA); access tokens expire daily.
- **`UpstoxMarketDataFeed implements MarketDataFeed`** — wraps the SDK's `MarketDataStreamerV3` WebSocket client in `LTPC` mode. Unchanged from the previous milestone.
- **`UpstoxOrderFillFeed implements OrderFillFeed`** — **now implements the broker-agnostic interface.** Listens to Upstox's portfolio-stream-feed WebSocket, filters for `status == "complete"` AND `transactionType == "BUY"` (case-insensitive), and surfaces `TradeFillEvent`s (now from `com.kbquants.session`).

> ⚠️ **Not connectivity-tested.** Same caveat as before: this sandbox blocks outbound access to `upstox.com`/`api.upstox.com`. The code was verified against the real SDK via `javap` decompilation and sources-jar inspection, and unit-tests cleanly, but the OAuth flow and both WebSocket connections have not been exercised end-to-end.

### 5.2 TradeMonitor — the central orchestrator (`com.kbquants.live`) — ✅ Implemented

**`TradeMonitor`** replaces the previous milestone's `LiveProfitAlertRunner`. It is the single orchestrator for both paper and (eventually) live trading, and implements `TelegramCommandListener` so it can be driven directly by Telegram commands as well as by a broker's `OrderFillFeed`.

Constructor: `TradeMonitor(OrderFillFeed, Function<TradeFillEvent, MarketDataFeed>, Notifier[, MilestoneLadder])`. The feed factory takes the whole `TradeFillEvent` (not just the instrument key) so a simulated feed can start at the trade's actual entry price.

On each confirmed fill (from either source):
- Builds a fresh `TradeContext` — **`basePrice` is set equal to `entryPrice`** for live/paper trades (breakeven protection once Phase 2 is reached). This differs from the simulation/backtest default of `entry × 2.0`, which is a research parameter, not a live-trading default (documented as an explicit choice in the class Javadoc).
- Starts an `ExitEngine` (full pipeline: hard safety, phase transitions, base protection, milestone ownership) and a `ProfitMilestoneTracker`, both driven by the same `MilestoneLadder`.
- Starts a `MarketDataFeed` for that instrument via the injected factory.

On each price tick:
- Runs `ExitEngine.onPriceUpdate()` — this is new; the previous MVP only ran the notification tracker, never the actual stop-loss/phase engine.
- If price has reached the (possibly just-updated) stop-loss, force-exits the trade and notifies.
- Otherwise, checks the milestone tracker and notifies on any newly crossed threshold.

Command handling (via `TelegramCommandListener`):
- `onBuy(instrumentKey, price, quantity)` — synthesizes a `TradeFillEvent` with a generated `telegram-<uuid>` order id and feeds it through the same path as a broker fill.
- `onExit(orderId)` — force-exits one trade; reports if the order id isn't found.
- `onExitAll()` — force-exits every open trade.
- `onStatusRequested()` — reports instrument, entry, current price, phase, stop-loss, and order id for every open trade.

**Test coverage:** `TradeMonitorTest` (11 tests) — fill → watch → milestone notify; duplicate-fill dedup; automatic stop-loss-triggered exit (and that no further processing happens after close); manual exit by order id; manual exit-all; status reporting (including the "no active trades" case); Telegram `/track` producing a tracked trade. All via fake `OrderFillFeed`/`MarketDataFeed` — no network touched.

## 6. Telegram: two-way control plane (`com.kbquants.notification`) — ✅ Implemented

Previously Telegram was outbound-only (alerts). It's now a full control plane: the trader can also *invoke* and *close* trades from Telegram, which is what makes the MVP testable end-to-end without a real broker.

- **`Notifier`** — unchanged, one-method interface (`send(String)`).
- **`TelegramCredentials`** — unchanged. Reads `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`.
- **`TelegramNotifier implements Notifier`** — unchanged. Outbound `sendMessage` calls via JDK `HttpClient`.
- **`ProfitMilestoneTracker`** — now reads its threshold ladder from `MilestoneLadder` (constructor overload `ProfitMilestoneTracker(entryPrice, MilestoneLadder)`, plus the original `(entryPrice)` and `(entryPrice, double[])` forms retained for direct/custom use). Default ladder now runs all the way to 55% (previously stopped at 13%), since ownership-lock milestones also produce notifications.
- **`TelegramCommandListener`** — **new.** Callback interface for inbound commands: `onBuy(instrumentKey, price, quantity)`, `onExit(orderId)`, `onExitAll()`, `onStatusRequested()`.
- **`TelegramCommandHandler`** — **new.** Long-polls Telegram's `getUpdates` Bot API on a background daemon thread (30s long-poll timeout), parses each message's text via a pure static `dispatch(text, listener)` method, and invokes the corresponding `TelegramCommandListener` method. Malformed commands (wrong arg count, non-numeric price/qty) are logged and ignored, never thrown. Command syntax:

  | Command | Effect |
  |---|---|
  | `/track <instrumentKey> <price> <qty>` | Invoke a new trade |
  | `/exit <orderId>` | Force-exit that trade |
  | `/exit all` (case-insensitive) | Force-exit every open trade |
  | `/status` | Report all open trades |

  JSON parsing of the `getUpdates` response uses Gson (already a transitive dependency of the Upstox SDK; pinned explicitly in `pom.xml` since it's now used directly).

**Test coverage:** `TelegramCommandHandlerTest` (10 tests) — covers the pure `dispatch` logic exhaustively (valid/malformed `/track`, `/exit <id>`, `/exit all` case-insensitivity, `/status`, unrecognized commands, null/blank text, extra whitespace tolerance). The actual HTTP long-polling loop is not unit-tested — same network caveat as §5.1 (`api.telegram.org` is blocked in this sandbox).

## 7. Paper trading entry point (`com.kbquants.Main`) — ✅ Implemented

`Main.main()` wires together a fully runnable paper-trading mode:

```
TelegramCredentials.fromEnv()
        │
        ├─→ TelegramNotifier (outbound)
        │
        └─→ TradeMonitor(NoOpOrderFillFeed, fill -> new SimulatedMarketDataFeed(fill.getAveragePrice(), 0.3), notifier)
                    │
                    └─→ TelegramCommandHandler(credentials, tradeMonitor).start()
```

- Reads `TRADING_MODE` env var (default `paper`); any other value logs an error and exits — live mode isn't wired yet (Phase 3).
- `TelegramCredentials.fromEnv()` fails fast with a clear `IllegalStateException` if `TELEGRAM_BOT_TOKEN`/`TELEGRAM_CHAT_ID` are missing (verified by running the packaged jar with no env vars set).
- A JVM shutdown hook stops the command handler cleanly.
- `mvn package` (via `maven-shade-plugin`) produces `target/xit-mc-1.0-SNAPSHOT.jar`, a runnable fat jar with all dependencies bundled and `Main-Class` set — verified to build and to fail-fast correctly with/without credentials in this sandbox (the actual Telegram network call is untestable here, same caveat as always).

Not unit-tested (it's a thin wiring `main()`, consistent with the project's testing rules — see CODING_STANDARDS.md §9); manually verified via `java -jar` runs.

## 8. Simulation layer (`com.kbquants.simulation`) — ✅ Implemented (generation, execution, reporting)

Unchanged from the previous milestone.

### 8.1 Price generation
- `ScenarioConfig` — immutable generation parameters: `length` (steps), `drift`, `volatility`, `pullbackStrength`, `seed`.
- `MarketRegime` (enum) — `BULLISH`, `BEARISH`, `CHOPPY`, `RANDOM`, biasing the drift direction.
- `RandomProvider` — thin seeded `java.util.Random` wrapper (`nextGaussian`, `nextDouble`) for reproducible randomness.
- `PricePathGenerator` (interface) → **`StochasticPricePathGenerator`** — the only implementation.
- `CandleGenerator` — 🟡 empty stub, not yet implemented.

**Test coverage:** `StochasticPricePathGeneratorTest` (8 tests).

### 8.2 Runner / orchestration (`simulation.runner`)
- `SimulationRequest`, `CombinationExecutor` → `SequentialCombinationExecutor` ✅ / `ParallelCombinationExecutor` ✅ (not wired into `SimulationRunner.resolveExecutor()` yet), `MetricsCollector`, `TradeMetrics`, `SimulationResult`, `SimulationRunner`. Unchanged.

**Test coverage:** `MetricsCollectorTest` (6 tests).

### 8.3 Reporting (`simulation.report`) — ✅ Implemented
`ConsoleReportFormatter`, `CsvReportFormatter`, `JsonReportFormatter`, `SimulationReporter`. Unchanged.

**Test coverage:** `ConsoleReportFormatterTest`, `CsvReportFormatterTest`, `JsonReportFormatterTest` (1 each), `SimulationReporterTest` (3).

## 9. Known gaps / explicitly unfinished areas

- **`com.kbquants.config` package** — still all empty class bodies. All parameters are hardcoded constants or read from `MilestoneLadder.defaultLadder()`.
- **`ExitModel` (Conservative/Moderate/Aggressive) has no behavioral differentiation** — same as before.
- **Parallel execution isn't reachable from `SimulationRunner`** — same as before.
- **`CandleGenerator`** — still an empty stub.
- **`hybridEnabled`/`hybridUpdateCount`/`atr`/`ownershipPercentage` fields on `TradeContext`** — still unused hooks.
- **`PHASE_4` (forced EOD exit)** — implemented via `EndOfDaySchedule`, opt-in through `EOD_EXIT_TIME`. Closes `MANAGED` trades at a wall-clock time in the market's zone; `OBSERVED` trades are warned about rather than sold, `RELEASED` ignored.
- **The order side is not wired into `Main`** — live *market data* is (`MARKET_DATA=live`), but `UpstoxOrderFillFeed` is still unused there, so trades only ever arrive via `/track`. Wiring it is Phase 3. When it happens, note that it streams fills for the **whole account**: adoption becomes opt-out, and `/pause` (or an explicit adopt step) is what prevents unrelated positions being managed.
- **The app never places buy orders** — it is purely an exit engine, placing sell orders for positions bought elsewhere. `/track` declares an existing position rather than ordering anything; the name is a legacy misnomer.
- **Ladder numbers are unvalidated** — the EQUITY/OPTIONS sets and their phase placements are reasoned starting points, not derived from data. The simulation layer cannot validate them either: it models GBM on the instrument, whereas an option premium is a convex function of the underlying plus time decay.
- **Sell-side charges are quoted at the entry price** at fill time, since the exit price is unknown. Drift is ~₹0.31 near breakeven and ~₹89 at a +100% exit; the settled figure reported at exit corrects it.
- **Order slicing is not implemented** — quantity is capped at the exchange freeze limit (27 lots for NIFTY) rather than split across orders, because multiple fills at different prices do not fit the single-entry-price model.
- **Instrument master is refreshed weekly, not daily** — contracts listed since the last Wednesday will not resolve until `/refresh`.
- **No real exit order placement** — `TradeMonitor` force-exits (marks the trade closed, sets SL to current price) but never places, modifies, or cancels a real broker order. Real limit/GTT exit orders are Phase 3.
- **Position reconciliation against the broker** — open trades now survive a restart via `JsonTradeStore`, but nothing checks them against the broker's actual positions. A trade closed by hand while the process was down is resumed regardless; the user is warned to check. Real reconciliation needs the order/position APIs, so it is Phase 3.
- **The Upstox live feeds are untested against real network/servers** — see §5.1. Same for Telegram's `getUpdates`/`sendMessage` calls — see §6.
- **Runner-level classes lack dedicated unit tests**: `SequentialCombinationExecutor`, `ParallelCombinationExecutor`, `SimulationRequest`, `SimulationResult`, and `SimulationRunner` — unchanged gap.

## 10. Test suite summary

```
309 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS
```

| Test class | Tests |
|---|---|
| `TelegramCommandHandlerTest` | 19 |
| `InstrumentAwareBuyRequestResolverTest` | 16 |
| `MilestoneSetsTest` | 14 |
| `TradeMonitorTest` | 13 |
| `InstrumentRegistryTest` | 12 |
| `TradeMonitorControlTest` | 11 |
| `MilestoneLadderTest` | 11 |
| `PositionSizerTest` | 10 |
| `TradeMonitorLadderTest` | 9 |
| `StochasticPricePathGeneratorTest` | 8 |
| `ProfitMilestoneTrackerTest` | 8 |
| `PositionSizerRiskTest` | 8 |
| `UpstoxMarketDataFeedTest` | 7 |
| `TradeCostTest` | 7 |
| `UpstoxOrderFillFeedTest` | 6 |
| `UpstoxDataCredentialsTest` | 6 |
| `StopLossEngineTest` | 6 |
| `PhaseManagerTest` | 6 |
| `MilestoneOwnershipStrategyTest` | 6 |
| `MetricsCollectorTest` | 6 |
| `InstrumentMasterLoaderTest` | 6 |
| `ExitEngineTest` | 6 |
| `EstimatedChargesServiceTest` | 6 |
| `TelegramNotifierTest` | 5 |
| `ContinuousOwnershipStrategyTest` | 5 |
| `UpstoxCredentialsTest` | 4 |
| `InstrumentCatalogTest` | 4 |
| `TradingSessionTest` | 3 |
| `TelegramCredentialsTest` | 3 |
| `SimulationReporterTest` | 3 |
| `SimulatedMarketDataFeedTest` | 3 |
| `UpstoxAuthServiceTest` | 2 |
| `JsonReportFormatterTest` | 1 |
| `DeterministicSimulationFeedTest` | 1 |
| `CsvReportFormatterTest` | 1 |
| `ConsoleReportFormatterTest` | 1 |

Run with: `mvn test`. Build a runnable jar with `mvn package`.

## 11. Suggested next steps (see IMPLEMENTATION_PLAN.md for the full phased roadmap)

1. **Wire live broker mode into `Main`** — add `UpstoxOrderFillFeed` + `UpstoxMarketDataFeed` as an alternative to the paper-mode wiring when `TRADING_MODE=live`, plus the daily OAuth token flow.
2. **Verify all Upstox/Telegram network paths against real servers** from an unrestricted environment: OAuth token exchange, both Upstox WebSocket feeds, Telegram `getUpdates`/`sendMessage`.
3. **Real exit order placement** (limit or GTT) in `TradeMonitor.forceExit`/stop-loss-hit path, via a new `ExitOrderPlacer` interface (broker-agnostic, mirroring `OrderFillFeed`/`MarketDataFeed`).
4. **Position reconciliation on startup** for live mode.
5. Implement `com.kbquants.config` so `MilestoneLadder` and other parameters are externally configurable (YAML/JSON).
6. Give `ExitModel` real behavioral differences.
7. Wire `ParallelCombinationExecutor` into `SimulationRunner.resolveExecutor()`.
8. Implement `CandleGenerator` and start consuming `Candle5m`.
9. Add direct unit tests for the `runner` package's orchestration classes.
10. Consider a `HISTORICAL` `MarketDataFeed` implementation for backtesting against real past data.
11. Decide the fate of unused `TradeContext` fields (`atr`, `ownershipPercentage`, `hybridEnabled`/`hybridUpdateCount`).
