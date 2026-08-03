# xit-mc — Development Documentation

> Status snapshot as of 2026-08-03. This document describes what has actually been implemented in the codebase so far — not the target design. Sections are marked ✅ Implemented, 🟡 Partial (stub/scaffolded), or ⬜ Not started.

## 1. What this project is

`xit-mc` (Maven artifact `com.kbquants:xit-mc`) is a Java 17 research engine for simulating and evaluating **exit strategies for trades** — specifically stop-loss and profit-ownership ("trailing lock-in") logic — against synthetically generated *and, as of this update, real live* price paths. It is primarily a backtesting/research sandbox, not a full live trading system: there is now a live market data connection (Upstox), but still no order execution/broker trading integration and no persistence layer.

The core question the codebase is built to answer is: *given a price path, how does a trade's stop-loss evolve under different combinations of exit "personality" (Conservative/Moderate/Aggressive) and profit-ownership strategy (Continuous vs. Milestone-based), and how does it perform (max favorable/adverse excursion, final phase, forced exit, etc.)?*

**Build:** Maven, Java 17, Lombok, Logback (JSON/structured logging via `logstash-logback-encoder`), Apache POI (declared, not yet used), the official Upstox Java SDK (`com.upstox.api:upstox-java-sdk:1.27`, live market data + order-fill events), JUnit 5. Telegram alerting uses only the JDK's built-in `java.net.http.HttpClient` — no extra dependency.
**Current state:** `mvn test` → **86/86 tests passing**, `BUILD SUCCESS`. No `main()` entry point exists yet — the project is a library of engine + simulation components exercised only by tests.

## 2. Package layout

```
com.kbquants
├── config       🟡  Configuration loading (all classes are empty stubs)
├── domain       ✅  Core value/state objects (Phase, TradeContext, enums, snapshots)
├── engine       ✅  The exit/stop-loss/ownership rules engine
├── live         ✅  Live market data + order-fill events via Upstox (OAuth login, two WebSocket feeds)
├── notification ✅  Telegram alerting (MVP stand-in for future order placement)
├── session      ✅  Wraps the engine into a "trading session" driven by a price feed
└── simulation   ✅  Synthetic price generation, batch execution ("runner"), and reporting
    ├── report   ✅  Console / CSV / JSON formatting of results
    └── runner   ✅  Orchestration of Exit×Ownership combinations over a price path
```

## 3. Domain model (`com.kbquants.domain`) — ✅ Implemented

| Type | Purpose |
|---|---|
| `TradeContext` | The single mutable state object for one trade: id, entry price, base price, quantity, current phase, current stop-loss, ATR, ownership %, hybrid-update count, `forceExited`/`isClosed` flags. Everything else in the engine reads/writes this object. |
| `Phase` (enum) | Trade lifecycle stage: `PHASE_1` (initial risk validation) → `PHASE_2` (base capital protection) → `PHASE_3` (profit protection) → `PHASE_4` (forced EOD exit, defined but not yet wired into transitions). |
| `ExitModel` (enum) | Exit "personality": `CONSERVATIVE`, `MODERATE`, `AGGRESSIVE`. Currently just a tag threaded through the engine/metrics — no model-specific behavior branches on it yet (see §7 gaps). |
| `OwnershipMode` (enum) | `CONTINUOUS` or `MILESTONE` — selects which `OwnershipStrategy` implementation is used. |
| `Candle5m` | Immutable OHLC + start/end timestamp for a completed 5-minute candle. Defined for future phase-transition/structured evaluation use; not yet consumed anywhere. |
| `MarketSnapshot` | Immutable `(ltp, timestamp)` tick snapshot, intended for future tick-level/hybrid processing; not yet consumed. |

## 4. Exit engine (`com.kbquants.engine`) — ✅ Implemented (core rules)

This is the heart of what's been built. It's a small, deterministic rules pipeline driven by `ExitEngine.onPriceUpdate(price, context)`, called once per price tick:

1. **Hard safety stop** (`StopLossEngine.applyHardSafety`) — always sets a floor stop-loss at entry price − 20%, every tick, regardless of phase.
2. **Phase transition** (`PhaseManager.evaluatePhaseTransition`):
   - `PHASE_1 → PHASE_2` when price ≥ entry × 1.06 (+6%)
   - `PHASE_2 → PHASE_3` when price ≥ entry × 1.13 (+13%)
   - `PHASE_3`/`PHASE_4` currently have no further automatic transition logic.
3. **Base protection** (`StopLossEngine.applyBaseProtectionIfEligible`) — once in `PHASE_2`, ratchets the stop-loss up to the trade's `basePrice` (never below it, never backward).
4. **Ownership strategy** (`OwnershipStrategy.apply`, only active in `PHASE_3`) — locks in a portion of open profit as the new stop-loss, via one of two pluggable strategies (factory-selected by `OwnershipStrategyFactory`):
   - **`ContinuousOwnershipStrategy`** — locks a flat **30%** of open profit (price − basePrice) continuously once in Phase 3.
   - **`MilestoneOwnershipStrategy`** — locks an increasing % of open profit at fixed profit-from-entry milestones:
     | Profit from entry | Ownership locked |
     |---|---|
     | ≥ 13% | 30% |
     | ≥ 21% | 50% |
     | ≥ 34% | 70% |
     | ≥ 55% | 85% |

All stop-loss writes go through `StopLossEngine.updateStopLoss`, which enforces a **monotonic ratchet** — a candidate SL is only applied if it's higher than the current one, so the stop-loss never moves against the trade.

`ExitEngine` also exposes `forceExit(context, price)` for an explicit forced close (sets SL to current price, marks `forceExited` and `isClosed`), and short-circuits all further processing once `context.isClosed()` is true.

**Test coverage:** `PhaseManagerTest`, `StopLossEngineTest`, `ContinuousOwnershipStrategyTest`, `MilestoneOwnershipStrategyTest`, `ExitEngineTest` (5–6 tests each, 28 tests total) cover threshold boundaries, ratchet-never-decreases behavior, phase gating (ownership strategies are no-ops outside Phase 3), and end-to-end sequencing through `ExitEngine`.

## 5. Session layer (`com.kbquants.session`) — ✅ Implemented

Wraps a `TradeContext` + `ExitEngine` behind a `PriceListener`, so it can be driven by any `MarketDataFeed`:

- `TradingSession` — holds `TradingMode` + `TradeContext`, forwards each `onPrice(price, timestamp)` tick into `ExitEngine.onPriceUpdate`, and stops forwarding once the trade is closed.
- `TradingMode` (enum) — `SIMULATION`, `HISTORICAL`, `PAPER`, `LIVE`. Only `SIMULATION` has a concrete feed implementation so far; the others are placeholders for future data sources.
- `MarketDataFeed` / `PriceListener` — small interfaces decoupling "where prices come from" from "what consumes them."
- `DeterministicSimulationFeed` — feeds a fixed, pre-built `List<Double>` of prices sequentially with synthetic incrementing timestamps. Used for deterministic, reproducible tests of the engine end-to-end (no randomness, no concurrency).

**Test coverage:** `TradingSessionTest` (3 tests), `DeterministicSimulationFeedTest` (1 test).

### 5.1 Live market data (`com.kbquants.live`) — ✅ Implemented (Upstox)

`TradingMode.LIVE` now has a real feed behind it. This package integrates with **Upstox** (`https://upstox.com`) via the official `com.upstox.api:upstox-java-sdk:1.27` Maven dependency, and plugs into the existing `MarketDataFeed`/`PriceListener` abstraction — no changes were needed to `TradingSession` itself.

- **`UpstoxCredentials`** — immutable holder for `apiKey`, `apiSecret`, `redirectUri`, `accessToken`, `sandbox`. `UpstoxCredentials.fromEnv()` reads `UPSTOX_API_KEY`, `UPSTOX_API_SECRET`, `UPSTOX_REDIRECT_URI` (required), `UPSTOX_ACCESS_TOKEN` (optional — see below), and `UPSTOX_SANDBOX` (optional, default `false`) from the process environment. No secrets are stored in the repo.
- **`UpstoxAuthService`** — implements Upstox's OAuth2 authorization-code flow:
  - `buildAuthorizationUrl(state)` → the URL to open in a browser (`GET /v2/login/authorization/dialog`).
  - `exchangeCodeForToken(code)` → exchanges the `code` from the redirect callback for an access token (`POST /v2/login/authorization/token`), via the SDK's `LoginApi`.
  - **This step is inherently manual/interactive** — Upstox requires a human login (password + TOTP/2FA) in a browser; there is no supported headless/fully-automated login. Access tokens expire daily (Upstox invalidates them each night), so this flow must be re-run once per trading day and the resulting token supplied as `UPSTOX_ACCESS_TOKEN` (or wired in programmatically) before starting the live feed.
- **`UpstoxMarketDataFeed implements MarketDataFeed`** — the live feed itself. Wraps the SDK's `MarketDataStreamerV3` WebSocket client in `LTPC` mode (last-traded-price-and-close only — the cheapest subscription tier, matching what the engine actually consumes). `start(listener)` connects asynchronously (auto-reconnect enabled) and forwards each tick as `listener.onPrice(ltp, lastTradedTime)` for every subscribed instrument key; `stop()` disconnects. Constructor validates that an access token and at least one instrument key are present, failing fast with a clear message otherwise.

**Test coverage:** `UpstoxCredentialsTest` (4), `UpstoxAuthServiceTest` (2 — authorization URL construction for prod/sandbox, with/without `state`), `UpstoxMarketDataFeedTest` (7 — constructor validation, plus the tick-mapping logic (`dispatchUpdate`) exercised directly against hand-built SDK payload objects). The actual WebSocket connection and OAuth token exchange are **not** exercised by tests — they require live network access and real credentials, which are unavailable in the environment these were developed in (see the callout below).

> ⚠️ **Not connectivity-tested.** The remote sandbox this was built in blocks all outbound access to `upstox.com`/`api.upstox.com` at the network-policy level (the proxy rejects the CONNECT tunnel outright). The code was written and verified against the real SDK — its classes were decompiled locally (`javap`) and its sources jar was inspected to confirm exact method signatures, request paths, and field names, rather than guessed — and it compiles and unit-tests cleanly. But end-to-end connectivity (OAuth token exchange, the WebSocket handshake, and live tick delivery) has **not** been verified against Upstox's actual servers. Test this from an environment with outbound network access before relying on it for anything live.

**Usage sketch:**
```java
UpstoxCredentials credentials = UpstoxCredentials.fromEnv();

// Once per trading day, if UPSTOX_ACCESS_TOKEN isn't already set:
UpstoxAuthService authService = new UpstoxAuthService(credentials);
System.out.println("Visit: " + authService.buildAuthorizationUrl(null));
// ... user logs in, app receives ?code=... on the redirect URI ...
String accessToken = authService.exchangeCodeForToken(code);

UpstoxMarketDataFeed feed = new UpstoxMarketDataFeed(
        new UpstoxCredentials(credentials.getApiKey(), credentials.getApiSecret(),
                credentials.getRedirectUri(), accessToken, credentials.isSandbox()),
        Set.of("NSE_EQ|INE848E01016"));

TradingSession session = new TradingSession(TradingMode.LIVE, tradeContext);
feed.start(session);
```

### 5.2 MVP: fill-triggered Telegram profit alerts (`com.kbquants.live` + `com.kbquants.notification`) — ✅ Implemented

The intended end-to-end system is: react to a real order fill → watch that trade's live price → act on the exit rules. This MVP slice implements the first two steps and, instead of acting (placing a real exit order), just **sends a Telegram notification** at a series of profit milestones. Placing real limit/GTT exit orders at these same milestones is explicitly the *next* step, not part of this MVP — nothing in this slice places, modifies, or cancels any order.

**Trigger — order fills, not our own decisions.** Entries are assumed to be placed elsewhere (this codebase never places a buy order); this system only reacts once Upstox confirms one filled.

- **`TradeFillEvent`** / **`TradeFillListener`** — a confirmed BUY fill (order id, instrument key, average fill price, filled quantity) and the callback interface for it.
- **`UpstoxOrderFillFeed`** — listens to Upstox's *other* WebSocket, the portfolio-stream-feed (`GET /v2/feed/portfolio-stream-feed/authorize`, order-updates only — position/holding/GTT updates are not requested), via the SDK's `PortfolioDataStreamer`. Filters for `transactionType == "BUY"` and `status == "complete"` (matched case-insensitively, since exact casing could not be verified against a live payload — see the network caveat in §5.1) and surfaces those as `TradeFillEvent`s. This is a genuinely separate WebSocket connection from the market-data feed in §5.1, each with its own Upstox "authorize" call and its own short-lived signed connection URL, confirmed by reading the SDK's decompiled source rather than assuming.

**Watch — reuse the existing live price feed.** On each fill, a `ProfitMilestoneTracker(entryPrice)` is created and a new `UpstoxMarketDataFeed` (§5.1) is started for just that instrument.

- **`ProfitMilestoneTracker`** (in `com.kbquants.notification`) — pure, per-trade ratchet: given the live price, returns the highest new profit-from-entry milestone crossed since the last check (or nothing), each milestone firing exactly once, ascending only — the same one-shot-ratchet pattern `PhaseManager` already uses for its 6%/13% transitions, just applied to a separate, finer-grained ladder: **0.5% → 1% → 2% → 3% → 5% → 8% → 13%** (`ProfitMilestoneTracker.DEFAULT_THRESHOLDS_PERCENT`). If price jumps past several thresholds in one tick, only the highest is reported (one message per tick, not one per skipped milestone).

**Notify — Telegram, swappable later.** `Notifier` is a one-method interface (`send(String)`) specifically so this step can later be replaced or supplemented with real order placement without redesigning the trigger/watch pieces.

- **`TelegramCredentials`** — `TelegramCredentials.fromEnv()` reads `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` from the environment (same no-secrets-in-repo pattern as `UpstoxCredentials`).
- **`TelegramNotifier implements Notifier`** — calls Telegram's `sendMessage` Bot API endpoint directly via the JDK's built-in `java.net.http.HttpClient` (no extra dependency needed for this part). A failed send is logged, never thrown — a notification hiccup must never interrupt live price monitoring.

**Orchestration — `LiveProfitAlertRunner`.** Wires the three pieces together: `start()` connects `UpstoxOrderFillFeed`; each fill creates a tracker and a per-instrument market-data feed; each price tick checks the tracker and calls the `Notifier` when a milestone is newly crossed. A fill is only tracked once per `orderId` (guards against a duplicate order-update delivery, e.g. after a WebSocket reconnect, opening a second redundant feed).

**Test coverage:** `ProfitMilestoneTrackerTest` (7), `TelegramCredentialsTest` (3), `TelegramNotifierTest` (2 — pure request-body construction only, not the real HTTP call), `UpstoxOrderFillFeedTest` (6 — the BUY+complete filtering logic against hand-built SDK payloads), `LiveProfitAlertRunnerTest` (3 — full orchestration exercised via an injectable fake `MarketDataFeed` factory, so no network is touched: verifies a fill starts watching the right instrument, milestones fire the right notification text, repeated/duplicate fills don't double-track).

> ⚠️ Same caveat as §5.1: the order-fill listener's WebSocket connection and Telegram's `sendMessage` call are both **untested against real network/servers** from this sandbox (both `upstox.com` and `api.telegram.org` are blocked by this environment's outbound network policy — confirmed directly, not assumed). Everything that doesn't require live network access is unit-tested; the two real network calls need verification from an unrestricted environment before relying on this for anything live.

## 6. Simulation layer (`com.kbquants.simulation`) — ✅ Implemented (generation, execution, reporting)

This layer generates synthetic price data and batch-executes the engine across combinations of exit models and ownership modes for research purposes.

### 6.1 Price generation
- `ScenarioConfig` — immutable generation parameters: `length` (steps), `drift`, `volatility`, `pullbackStrength`, `seed`.
- `MarketRegime` (enum) — `BULLISH`, `BEARISH`, `CHOPPY`, `RANDOM`, biasing the drift direction.
- `RandomProvider` — thin seeded `java.util.Random` wrapper (`nextGaussian`, `nextDouble`) for reproducible randomness.
- `PricePathGenerator` (interface) → **`StochasticPricePathGenerator`** — the only implementation. Each step combines regime-adjusted drift + Gaussian noise × volatility + a mean-reverting pullback term proportional to the previous move, floored at a minimum price of 1.0.
- `CandleGenerator` — 🟡 empty stub, not yet implemented (intended to roll ticks into `Candle5m` objects).

**Test coverage:** `StochasticPricePathGeneratorTest` (8 tests) — covers determinism under a fixed seed, regime bias direction (bullish drifts up, bearish drifts down), price floor, and output length.

### 6.2 Runner / orchestration (`simulation.runner`)
- `SimulationRequest` — immutable, validated request bundling `MarketRegime`, `ScenarioConfig`, the list of `ExitModel`s and `OwnershipMode`s to test, and an `ExecutionMode` (`SEQUENTIAL`/`PARALLEL`). Rejects nulls and empty model/mode lists.
- `CombinationExecutor` (interface) — runs every `ExitModel × OwnershipMode` combination over one shared price path and returns a `SimulationResult`.
  - **`SequentialCombinationExecutor`** ✅ — for each combination, builds a fresh `TradeContext` (entry price = first price in path, base price = entry × 2.0, quantity = 1) and a fresh `ExitEngine`, replays the full price path tick-by-tick (stopping early if the trade closes), and records metrics via `MetricsCollector`. Fully isolated per combination — no shared state.
  - **`ParallelCombinationExecutor`** ✅ — same semantics, but submits one `Callable` per combination to a fixed-size `ExecutorService`, delegating actual per-combination work to `SequentialCombinationExecutor` under the hood, then collects `Future` results.
  - Note: `SimulationRunner.resolveExecutor` currently **throws `UnsupportedOperationException` for `ExecutionMode.PARALLEL`** — the parallel executor class exists and works standalone, but isn't wired into the top-level `SimulationRunner` yet (see §7).
- `MetricsCollector` — pure observer (no engine influence) that tracks, per replay: max favorable/adverse excursion vs. entry price, whether ownership ever activated, whether "hybrid" update ever activated, and whether the trade was force-exited; builds an immutable `TradeMetrics` at the end.
- `TradeMetrics` — immutable per-combination result: exit model, ownership mode, final stop-loss, final phase, MFE, MAE, ownership/hybrid/forceExit/closed flags.
- `SimulationResult` — immutable wrapper around a `List<TradeMetrics>` (one per combination). No aggregation/ranking logic yet.
- `SimulationRunner` — top-level orchestrator: generates the price path via the injected `PricePathGenerator`, resolves an executor for the requested `ExecutionMode`, runs it, and logs structured timing/summary info.

**Test coverage:** `MetricsCollectorTest` (6 tests). *(No dedicated test classes yet for `SequentialCombinationExecutor`, `ParallelCombinationExecutor`, `SimulationRequest`, `SimulationResult`, or `SimulationRunner` themselves — see §7.)*

### 6.3 Reporting (`simulation.report`) — ✅ Implemented
- `ConsoleReportFormatter` — renders a `SimulationResult` as an aligned plain-text table (ExitModel, Ownership, FinalSL, Phase, MFE, MAE, Closed).
- `CsvReportFormatter` — same data as CSV with a header row.
- `JsonReportFormatter` — same data as a hand-built JSON string (no external JSON library used — manual string concatenation).
- `SimulationReporter` — facade over the three formatters: `printToConsole`, `exportAsJson`, `exportAsCsv`, with null-checks and structured logging.

**Test coverage:** `ConsoleReportFormatterTest`, `CsvReportFormatterTest`, `JsonReportFormatterTest` (1 test each), `SimulationReporterTest` (3 tests).

## 7. Known gaps / explicitly unfinished areas

These are things that are scaffolded (interfaces, enums, empty classes) but not yet functional — worth knowing so they aren't mistaken for working features:

- **`com.kbquants.config` package** — `ConfigurationFactory`, `ConfigurationLoader`, `EngineConfig`, `JsonConfigurationLoader`, `SimulationConfig`, `YamlConfigurationLoader` are all **empty class bodies**. No config-driven setup exists yet; all parameters (thresholds, percentages, seeds) are hardcoded constants inside the relevant engine/simulation classes.
- **No `main()` / CLI / entry point** — the project can currently only be exercised through JUnit tests; there's no runnable application yet tying `SimulationRunner` + `SimulationReporter` together.
- **`ExitModel` (Conservative/Moderate/Aggressive) has no behavioral differentiation** — the enum is threaded through `TradeContext`/`TradeMetrics` but no engine component currently branches on its value; all trades behave identically regardless of which `ExitModel` is selected.
- **Parallel execution isn't reachable from `SimulationRunner`** — `ParallelCombinationExecutor` is implemented and presumably testable in isolation, but `SimulationRunner.resolveExecutor()` throws `UnsupportedOperationException` for `ExecutionMode.PARALLEL`, so it can't be driven end-to-end yet.
- **`CandleGenerator`** is an empty stub — no tick→5m candle aggregation yet, so `Candle5m` is currently unused.
- **`hybridEnabled`/`hybridUpdateCount`/`atr`/`ownershipPercentage` fields on `TradeContext`** are declared and have getters/setters (and are read by `MetricsCollector`), but nothing in the engine currently writes to `atr`, `ownershipPercentage`, or increments `hybridUpdateCount` — these look like hooks for planned-but-unbuilt features (ATR-based sizing, a "hybrid" tick/candle update mode).
- **`PHASE_4` (forced EOD exit)** is defined in the `Phase` enum but `PhaseManager` has no transition logic into it — forced exit today only happens via the explicit `ExitEngine.forceExit()` call, not automatically at end-of-day.
- **`HISTORICAL`/`PAPER` trading modes** (`TradingMode`) are still declared but have no corresponding `MarketDataFeed` implementations — only `SIMULATION` (`DeterministicSimulationFeed`) and now `LIVE` (`UpstoxMarketDataFeed`, see §5.1) are functional.
- **The Upstox live feeds are untested against real network/servers** — see the callouts in §5.1/§5.2. They were implemented against the real SDK's decompiled bytecode/sources (not guessed), and pass unit tests for everything that doesn't require network access, but the OAuth token exchange and both WebSocket connections (market data, order fills) have not been exercised end-to-end because this sandbox blocks outbound access to `upstox.com`. Same for Telegram's `sendMessage` call (`api.telegram.org` is also blocked here).
- **No order placement at all yet** — `LiveProfitAlertRunner` (§5.2) only *watches* and *notifies*; it never places, modifies, or cancels an order. Real exit execution (limit or GTT orders at the same profit milestones) is the explicitly-planned next step, not built. There is also still no entry-order placement (by design — entries are assumed to happen elsewhere) and no persistence layer (despite Apache POI being a declared dependency, nothing currently reads/writes spreadsheets).
- **The profit-milestone ladder (0.5%/1%/2%/3%/5%/8%/13%) is independent of the core exit engine** — `ProfitMilestoneTracker` does not read or influence `TradeContext`/`ExitEngine`/`PhaseManager` in any way; it's a parallel, notification-only concept. If/when real exit orders replace the Telegram alert, revisit whether this ladder should instead reuse or align with the existing Phase/ownership thresholds.
- **Runner-level classes lack dedicated unit tests**: `SequentialCombinationExecutor`, `ParallelCombinationExecutor`, `SimulationRequest`, `SimulationResult`, and `SimulationRunner` have no test classes of their own yet (only exercised indirectly).

## 8. Test suite summary

```
86 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS
```

| Test class | Tests |
|---|---|
| `ExitEngineTest` | 6 |
| `StopLossEngineTest` | 6 |
| `MilestoneOwnershipStrategyTest` | 6 |
| `MetricsCollectorTest` | 6 |
| `ProfitMilestoneTrackerTest` | 7 |
| `UpstoxMarketDataFeedTest` | 7 |
| `UpstoxOrderFillFeedTest` | 6 |
| `ContinuousOwnershipStrategyTest` | 5 |
| `PhaseManagerTest` | 5 |
| `StochasticPricePathGeneratorTest` | 8 |
| `UpstoxCredentialsTest` | 4 |
| `TradingSessionTest` | 3 |
| `SimulationReporterTest` | 3 |
| `LiveProfitAlertRunnerTest` | 3 |
| `TelegramCredentialsTest` | 3 |
| `UpstoxAuthServiceTest` | 2 |
| `TelegramNotifierTest` | 2 |
| `DeterministicSimulationFeedTest` | 1 |
| `ConsoleReportFormatterTest` | 1 |
| `CsvReportFormatterTest` | 1 |
| `JsonReportFormatterTest` | 1 |

Run with: `mvn test`

## 9. Suggested next steps (not yet started)

1. **Verify all three Upstox/Telegram network paths against real servers** — from an environment that isn't blocked (this sandbox is): the OAuth token exchange, both WebSocket feeds (market data, order fills), and the Telegram `sendMessage` call. Confirm exact `status`/`transactionType` string casing from a real order-update payload (currently matched case-insensitively as a hedge, per §5.2).
2. **Replace/augment the Telegram alert with real exit orders** (limit or GTT) at the same profit milestones — this was explicitly called out as the step after this MVP. `Notifier` was deliberately kept as a one-method interface so this can plug in alongside or instead of `TelegramNotifier` without redesigning `LiveProfitAlertRunner`.
3. Implement `com.kbquants.config` so thresholds/percentages/seeds (and possibly the Upstox/Telegram credentials) are externally configurable (JSON/YAML) instead of hardcoded constants/env vars.
4. Give `ExitModel` real behavioral differences (e.g., varying the hard-safety %, phase trigger %, or ownership curve per model).
5. Wire `ParallelCombinationExecutor` into `SimulationRunner.resolveExecutor()`.
6. Add a `main()`/CLI entry point that builds a `SimulationRequest`, runs `SimulationRunner`, and prints via `SimulationReporter`.
7. Implement `CandleGenerator` and start consuming `Candle5m`.
8. Add direct unit tests for the `runner` package's orchestration classes.
9. Consider a `HISTORICAL` `MarketDataFeed` implementation (Upstox also exposes historical candle APIs) to backtest against real past data instead of only synthetic price paths.
10. Decide the fate of unused `TradeContext` fields (`atr`, `ownershipPercentage`, `hybridEnabled`/`hybridUpdateCount`) — either build the features they hint at, or remove them.
