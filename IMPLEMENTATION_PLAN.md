# xit-mc Implementation Plan

> Version 1.0 — August 2026

This document is the phased roadmap for building xit-mc from its current state to a production-ready exit management system. Each phase lists concrete deliverables, the files/packages affected, dependencies, and acceptance criteria.

## Current state

- **86/86 tests passing**, BUILD SUCCESS
- Exit engine (phase transitions, stop-loss ratchet, ownership strategies) — complete
- Simulation framework (price generation, batch execution, reporting) — complete
- Live monitoring MVP (order fills, market data, Telegram alerts at profit milestones) — complete
- No `main()` entry point, no real exit orders, no external config, no persistence

## Phase 2: Real exit orders

**Goal:** Replace Telegram-only alerts with actual limit/GTT exit orders on Upstox at the same profit milestones.

### 2.1 Upstox order placement service

| Item | Detail |
|---|---|
| New class | `com.kbquants.live.UpstoxOrderService` |
| Responsibility | Place, modify, cancel limit/GTT orders via Upstox REST API (SDK's `OrderApi`) |
| Methods | `placeLimitSellOrder(instrumentKey, qty, price)`, `placeGttOrder(instrumentKey, qty, triggerPrice, limitPrice)`, `cancelOrder(orderId)` |
| Error handling | Log and return result status; never throw — mirrors TelegramNotifier's resilience pattern |
| Credential source | Same `UpstoxCredentials` used by feed classes |

### 2.2 OrderPlacingNotifier

| Item | Detail |
|---|---|
| New class | `com.kbquants.notification.OrderPlacingNotifier implements Notifier` |
| Behavior | On `send(message)`, parse the milestone context and place a limit/GTT sell order; optionally also forward to TelegramNotifier |
| Design | Composite pattern — wraps both order placement and Telegram notification behind the existing `Notifier` interface |
| Alternative | If message parsing is fragile, extend the `Notifier` interface to accept structured data (`Notifier.send(MilestoneEvent)`) or create a separate callback |

### 2.3 Position reconciliation on startup

| Item | Detail |
|---|---|
| New class | `com.kbquants.live.PositionReconciler` |
| Behavior | On startup, query Upstox for open positions (`PortfolioApi.getPositions()`); for each open BUY position, synthesize a `TradeFillEvent` and feed it into `LiveProfitAlertRunner.onFill()` |
| Purpose | Resume monitoring positions that were filled while xit-mc was offline |
| Prerequisite | Access token must be available at startup |

### 2.4 Wire ExitEngine into live monitoring

| Item | Detail |
|---|---|
| Modified class | `LiveProfitAlertRunner` (or new `LiveExitRunner`) |
| Behavior | On each price tick, run both `ProfitMilestoneTracker` (for notifications/orders) and `ExitEngine.onPriceUpdate()` (for stop-loss management) |
| New responsibility | When ExitEngine determines a stop-loss hit (price <= currentStopLoss), trigger the actual exit (sell order) |
| TradeContext source | Create a `TradeContext` per fill, initialized from the fill's entry price |

### 2.5 Tests

- `UpstoxOrderServiceTest` — verify request construction against SDK method signatures (same javap-verified approach)
- `OrderPlacingNotifierTest` — verify it delegates to both order service and Telegram notifier
- `PositionReconcilerTest` — verify open positions are converted to fill events
- `LiveExitRunnerTest` — verify stop-loss hit triggers exit order placement

### Acceptance criteria

- [ ] When price crosses a profit milestone, a limit/GTT sell order is placed on Upstox AND a Telegram notification is sent
- [ ] On startup, any open BUY positions from Upstox are automatically picked up for monitoring
- [ ] ExitEngine's stop-loss ratchet runs on every live price tick
- [ ] When price drops to or below the current stop-loss, a sell order is placed
- [ ] All new code has unit tests (network calls tested via fakes)
- [ ] Existing 86 tests still pass

---

## Phase 3: Production hardening

**Goal:** Make the system runnable, configurable, and operationally sound.

### 3.1 External configuration

| Item | Detail |
|---|---|
| Package | `com.kbquants.config` (currently empty stubs) |
| Format | YAML as primary, JSON as alternative |
| Scope | All hardcoded constants: phase trigger percentages (6%, 13%), hard safety (20%), ownership percentages, milestone thresholds, continuous ownership rate (30%) |
| Library | SnakeYAML (add to pom.xml) or Jackson YAML |
| Pattern | `EngineConfig` and `SimulationConfig` become real classes that load from file, with sensible defaults if no file is present |

### 3.2 CLI entry point

| Item | Detail |
|---|---|
| New class | `com.kbquants.Main` |
| Subcommands | `live` (start live monitoring), `simulate` (run simulation), `auth` (interactive OAuth flow) |
| Library | picocli (lightweight CLI framework) or plain args parsing |
| Behavior | `live`: reads env vars + config file, starts `LiveExitRunner`; `simulate`: reads config, runs `SimulationRunner` + `SimulationReporter`; `auth`: opens browser for OAuth, exchanges code, prints token |

### 3.3 ExitModel behavioral differentiation

| Item | Detail |
|---|---|
| Modified classes | `PhaseManager`, `StopLossEngine`, ownership strategies |
| Conservative | Tighter hard safety (15%), earlier phase triggers, higher ownership lock |
| Moderate | Current defaults (20% safety, 6%/13% triggers, 30% continuous) |
| Aggressive | Wider hard safety (25%), later phase triggers, lower initial ownership lock |
| Config-driven | The per-model parameters come from `EngineConfig`, not hardcoded |

### 3.4 PHASE_4 automation (forced EOD exit)

| Item | Detail |
|---|---|
| Modified class | `PhaseManager` |
| Behavior | Auto-transition to PHASE_4 at a configurable time (e.g., 15:15 IST for NSE) |
| Action | `ExitEngine.forceExit()` is called, which places a market/limit sell order |

### 3.5 Wire parallel executor

| Item | Detail |
|---|---|
| Modified class | `SimulationRunner.resolveExecutor()` |
| Change | Replace `throw UnsupportedOperationException` with `new ParallelCombinationExecutor(...)` |

### 3.6 Tests

- Config loading tests (YAML/JSON parsing, defaults, missing file handling)
- CLI integration tests (argument parsing, subcommand dispatch)
- ExitModel differentiation tests (verify Conservative/Moderate/Aggressive produce different stop-loss behavior)
- PHASE_4 transition tests
- ParallelCombinationExecutor wired through SimulationRunner

### Acceptance criteria

- [ ] `java -jar xit-mc.jar live` starts live monitoring with config from `config.yml`
- [ ] `java -jar xit-mc.jar simulate` runs a batch simulation and prints results
- [ ] All thresholds/percentages configurable without code changes
- [ ] Conservative, Moderate, and Aggressive exit models produce measurably different behavior
- [ ] Trades auto-close at EOD (configurable time)
- [ ] All existing + new tests pass

---

## Phase 4: Observability and operations

**Goal:** Make the system observable, resilient, and safe to run unattended.

### 4.1 Structured logging with correlation

| Item | Detail |
|---|---|
| Approach | Add `tradeId` / `orderId` as MDC context to all log lines within a trade's lifecycle |
| Library | Already have Logback + logstash-logback-encoder — just wire MDC |
| Benefit | Filter logs by trade in production; correlate across fill detection, price monitoring, and order placement |

### 4.2 Health checks

| Item | Detail |
|---|---|
| New class | `com.kbquants.health.HealthChecker` |
| Checks | WebSocket connection state (both feeds), last price tick recency, pending orders, access token validity/expiry |
| Exposure | Simple HTTP endpoint (JDK HttpServer, no framework) or periodic log-based health dump |

### 4.3 Graceful shutdown

| Item | Detail |
|---|---|
| Behavior | On SIGTERM/SIGINT: stop accepting new fills, close all market data feeds, log final state of all tracked trades |
| Implementation | JVM shutdown hook |
| Future | Persist tracked trades to file/DB so they can be resumed on next startup |

### 4.4 Alert escalation

| Item | Detail |
|---|---|
| Behavior | If a critical event occurs (stop-loss triggered, WebSocket disconnected for > N seconds, access token expiring), send an urgent notification separate from milestone alerts |
| Channels | Telegram (different chat/group), email, or push notification |
| Implementation | New `UrgentNotifier` or priority flag on existing `Notifier` |

### 4.5 Historical data feed

| Item | Detail |
|---|---|
| New class | `com.kbquants.session.HistoricalDataFeed implements MarketDataFeed` |
| Source | Upstox historical candle API |
| Purpose | Backtest exit strategies against real past price data (not just synthetic) |
| Mode | `TradingMode.HISTORICAL` finally gets a concrete implementation |

### Acceptance criteria

- [ ] Every log line within a trade lifecycle carries a correlation ID
- [ ] Health status is queryable (HTTP or log) and reports WebSocket state + last tick time
- [ ] System shuts down cleanly without orphaned WebSocket connections
- [ ] Critical events (stop-loss hit, disconnect) trigger urgent alerts
- [ ] Historical backtesting works against real Upstox data

---

## Dependency graph

```
Phase 2 (exit orders)
    |
    v
Phase 3 (hardening)    <-- can partially overlap with Phase 2
    |
    v
Phase 4 (observability) <-- can partially overlap with Phase 3
```

Phase 2 is the critical path — it turns xit-mc from a notification tool into an actual exit management system. Phase 3 and 4 items can be interleaved as needed.

## File impact summary

| Phase | New files | Modified files |
|---|---|---|
| 2 | `UpstoxOrderService`, `OrderPlacingNotifier`, `PositionReconciler`, `LiveExitRunner` (or modify `LiveProfitAlertRunner`) | `pom.xml` (if new deps needed) |
| 3 | `Main`, config YAML schema, `EngineConfig`/`SimulationConfig` (rewrite from stubs) | `PhaseManager`, `StopLossEngine`, ownership strategies, `SimulationRunner`, `pom.xml` |
| 4 | `HealthChecker`, `HistoricalDataFeed`, `UrgentNotifier` | `LiveExitRunner` (shutdown hooks, MDC), `TradingMode` |

## Open decisions (to be made before starting each phase)

### Before Phase 2
- Limit orders or GTT orders — or both? GTT persists across sessions but has broker-side limitations; limit orders are simpler but die on disconnect.
- Should `Notifier` be extended to accept structured data, or should order placement be a separate callback parallel to `Notifier`?

### Before Phase 3
- YAML library choice: SnakeYAML (lighter, YAML-only) vs Jackson (heavier, supports YAML + JSON + properties)
- CLI framework: picocli (annotation-based, auto-help) vs manual args parsing (zero deps)
- How is `basePrice` determined for live trades? Currently `entry x 2.0` in simulation — is that the intended live behavior?

### Before Phase 4
- Persistence layer: flat file, SQLite, or skip persistence and rely on position reconciliation?
- Health endpoint: HTTP server (adds complexity) or periodic log dump (simpler, grep-friendly)?
