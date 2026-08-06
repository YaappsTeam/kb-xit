# xit-mc Implementation Plan

> Version 2.0 — August 2026

Phased roadmap from the current state to a production-ready exit management system. Each step lists concrete deliverables, files affected, and acceptance criteria.

## Current state

- **116/116 tests passing**, BUILD SUCCESS. `mvn package` produces a runnable fat jar (`target/xit-mc-1.0-SNAPSHOT.jar`).
- Exit engine (phase transitions, stop-loss ratchet, ownership strategies) — complete
- Simulation framework (price generation, batch execution, reporting) — complete
- Upstox broker implementation (OAuth, market data WebSocket, order fill WebSocket) — complete, broker-agnostic-compatible, not yet wired into `Main`
- **Phase 2 (Testable MVP) is complete** — see the checked-off acceptance criteria below. Paper trading works end-to-end via Telegram.
- **Remaining gaps going into Phase 3:** live broker mode not wired into `Main`, no real exit order placement, no position reconciliation on startup. See DEVELOPMENT.md §9 for the full gap list.

---

## Phase 2: Testable MVP

**Goal:** A fully testable end-to-end flow. User triggers a trade via Telegram, system monitors price (simulated or live), sends milestone alerts, user can force exit. No real broker needed.

### Step 2.1 — Broker abstraction: OrderFillFeed interface

The `MarketDataFeed` interface already makes price sources pluggable. The order-fill side has no equivalent — `UpstoxOrderFillFeed` is directly instantiated. Fix this.

| Item | Detail |
|---|---|
| New interface | `com.kbquants.session.OrderFillFeed` |
| Methods | `void start(TradeFillListener listener)`, `void stop()` |
| Move | `TradeFillEvent` and `TradeFillListener` from `com.kbquants.live` to `com.kbquants.session` (they are broker-agnostic contracts, not Upstox-specific) |
| Refactor | `UpstoxOrderFillFeed implements OrderFillFeed` |
| Refactor | `LiveProfitAlertRunner` (or its successor) takes `OrderFillFeed` as a constructor parameter, not `UpstoxCredentials` |

**Why first:** Everything else in this phase depends on the fill source being pluggable.

**Tests:**
- Existing `UpstoxOrderFillFeedTest` still passes (only `toFillEvent` logic tested, no interface change)
- Existing `LiveProfitAlertRunnerTest` still passes (already uses a fake `MarketDataFeed`; will now also use a fake `OrderFillFeed`)

### Step 2.2 — Unified milestone ladder

Replace the three independent threshold systems with one configurable ladder.

**Current (broken) state:**
```
ProfitMilestoneTracker: [0.5, 1.0, 2.0, 3.0, 5.0, 8.0, 13.0] %  — notification only
PhaseManager:           PHASE_1→2 at 6%, PHASE_2→3 at 13%         — independent
MilestoneOwnership:     13%, 21%, 34%, 55%                         — independent
```

6% is not in the milestone ladder. Change milestones and phases don't follow.

**Target (unified) state:**
```
Single ladder: [0.5, 1.0, 2.0, 3.0, 5.0, 8.0, 13.0, 21.0, 34.0, 55.0] %

Each milestone can trigger:
  - Notification (always)
  - Phase transition (at configured milestones)
  - Ownership lock increase (at configured milestones)
```

| Milestone | Notification | Phase | Ownership |
|---|---|---|---|
| 0.5% | Yes | — | — |
| 1.0% | Yes | — | — |
| 2.0% | Yes | — | — |
| 3.0% | Yes | — | — |
| 5.0% | Yes | PHASE_1 → PHASE_2 | — |
| 8.0% | Yes | — | — |
| 13.0% | Yes | PHASE_2 → PHASE_3 | Lock 30% |
| 21.0% | Yes | — | Lock 50% |
| 34.0% | Yes | — | Lock 70% |
| 55.0% | Yes | — | Lock 85% |

| Item | Detail |
|---|---|
| New class | `com.kbquants.domain.MilestoneLadder` — single source of truth for all thresholds |
| Structure | Ordered list of `Milestone(percent, phaseTransition, ownershipLockPercent)` |
| Configurable | Constructor accepts the ladder; static factory provides the default |
| Refactor | `PhaseManager` reads phase triggers FROM the ladder, not from hardcoded 6%/13% |
| Refactor | `MilestoneOwnershipStrategy` reads ownership locks FROM the ladder, not from hardcoded map |
| Refactor | `ProfitMilestoneTracker` reads notification thresholds FROM the ladder |
| Result | Change the ladder in one place → notifications, phases, and ownership all follow |

**Tests:**
- `MilestoneLadderTest` — verify default ladder, custom ladder, ordering
- Update `PhaseManagerTest` — PHASE_1→2 now at 5% (was 6%), PHASE_2→3 still at 13%
- Update `ProfitMilestoneTrackerTest` — ladder now extends to 55% (ownership milestones also notify)
- Update `MilestoneOwnershipStrategyTest` — ownership thresholds come from ladder
- All existing engine tests updated for new 5% threshold (was 6%)

### Step 2.3 — Telegram as two-way control plane

Telegram is currently one-way (outbound notifications). Make it two-way: the user sends commands, the system responds.

| Command | Action |
|---|---|
| `/buy <instrument> <price> <qty>` | Create a `TradeFillEvent` and start monitoring |
| `/exit <orderId>` | Force exit that trade |
| `/exit all` | Force exit all active trades |
| `/status` | List active trades with current price, phase, milestone, and stop-loss |

| Item | Detail |
|---|---|
| New class | `com.kbquants.notification.TelegramCommandHandler` |
| Responsibility | Long-poll Telegram `getUpdates` API, parse commands, dispatch to `TradeMonitor` |
| Library | JDK `java.net.http.HttpClient` (same as `TelegramNotifier` — no extra deps) |
| Threading | Single background thread polling for updates |
| Order ID | For Telegram-triggered buys: generate a UUID (no real broker order ID exists) |

| New class | `com.kbquants.notification.TelegramBot` |
| Responsibility | Combines `TelegramNotifier` (outbound) + `TelegramCommandHandler` (inbound) into a single lifecycle |

**Tests:**
- `TelegramCommandHandlerTest` — parse valid/invalid commands, verify correct `TradeFillEvent` output
- No real Telegram API calls in tests (same network caveat as `TelegramNotifierTest`)

### Step 2.4 — Paper trading: SimulatedMarketDataFeed

A simulated price feed for paper trading. Unlike `DeterministicSimulationFeed` (which replays a fixed list synchronously), this one generates a continuous stream of random-walk prices around the entry price on a timer.

| Item | Detail |
|---|---|
| New class | `com.kbquants.session.SimulatedMarketDataFeed implements MarketDataFeed` |
| Behavior | On `start()`, begins a scheduled timer (e.g., every 1 second) that generates a new price via random walk and calls `listener.onPrice(price, timestamp)` |
| Parameters | Starting price, volatility, tick interval |
| Purpose | Paper trading — full pipeline runs without a real broker |

For the order fill side, the Telegram `/buy` command (step 2.3) IS the fill source in paper mode — no separate `SimulatedOrderFillFeed` needed. The `TelegramCommandHandler` directly creates a `TradeFillEvent` and passes it to the orchestrator.

**Tests:**
- `SimulatedMarketDataFeedTest` — verify prices are generated, listener is called, feed can be stopped

### Step 2.5 — Wire ExitEngine into live monitoring

Currently `LiveProfitAlertRunner` only runs `ProfitMilestoneTracker` on each tick. The full `ExitEngine` (stop-loss ratchet, phase transitions, ownership) is not wired in. Fix this.

| Item | Detail |
|---|---|
| New class | `com.kbquants.live.TradeMonitor` (replaces `LiveProfitAlertRunner`) |
| Per-trade state | `TradeContext` + `ExitEngine` + milestone tracking (from unified ladder) |
| On each tick | Run `ExitEngine.onPriceUpdate()` AND check milestone ladder |
| On stop-loss hit | If price <= currentStopLoss, close the trade and notify |
| On force exit | Set stop-loss to current price, mark closed, notify |
| Constructor | Takes `OrderFillFeed`, `Function<String, MarketDataFeed>`, `Notifier`, `MilestoneLadder` — all interfaces |

**Tests:**
- `TradeMonitorTest` — full orchestration: fill → price ticks → milestones fire → stop-loss managed → exit triggers
- Uses fake `OrderFillFeed`, fake `MarketDataFeed`, `RecordingNotifier`

### Step 2.6 — Integration: paper trading end-to-end

Wire everything together for a runnable paper trading mode.

| Item | Detail |
|---|---|
| New class | `com.kbquants.Main` (simple, just for paper trading MVP) |
| Behavior | Reads `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `TRADING_MODE` from env vars. Starts `TelegramBot` + `TradeMonitor` with `SimulatedMarketDataFeed` |
| Result | `java -jar xit-mc.jar` → bot is listening → user sends `/buy INFY 1500 10` → simulated prices flow → milestone alerts → user sends `/exit` → done |

### Acceptance criteria (Phase 2 complete when all are true)

- [x] `OrderFillFeed` interface exists; `UpstoxOrderFillFeed` implements it; orchestrator takes the interface
- [x] One `MilestoneLadder` configures notifications, phase transitions, AND ownership locks
- [x] Phase triggers are at milestone values (5% and 13%), not independent hardcoded values
- [x] Telegram bot accepts `/buy`, `/exit`, `/status` commands
- [x] Paper trading mode works end-to-end: Telegram trigger → simulated prices → milestone alerts → force exit
- [x] `ExitEngine` runs on every live tick (stop-loss ratchet, phases, ownership)
- [x] Stop-loss hit triggers automatic exit notification
- [x] `TradeFillEvent` and `TradeFillListener` live in `session` package (broker-agnostic)
- [x] Swapping `SimulatedMarketDataFeed` for `UpstoxMarketDataFeed` requires zero orchestrator changes (feed factory takes `TradeFillEvent`, returns `MarketDataFeed` — same shape for both)
- [x] All new code has unit tests; all existing tests pass (updated for 5% phase trigger); 116/116 passing

**Note on `LiveProfitAlertRunner`:** deleted rather than kept alongside `TradeMonitor` — it was a strict subset of `TradeMonitor`'s behavior (notifications only, no `ExitEngine`, no force-exit, no Telegram commands), so keeping both would have meant two orchestrators with overlapping responsibility. All of its tests were ported to `TradeMonitorTest`.

**Note on `TelegramBot` facade:** the plan originally proposed a `TelegramBot` class combining `TelegramNotifier` + `TelegramCommandHandler`. Skipped in favor of wiring both directly in `Main` — a facade would need the `TelegramCommandListener` (`TradeMonitor`) at construction time, but `TradeMonitor` itself needs a `Notifier` at construction time, creating a circular-construction problem the facade would have to work around with mutable setters. Direct wiring in `Main` (notifier first, then `TradeMonitor`, then `TelegramCommandHandler`) avoids the cycle with no added abstraction.

### Implementation order

```
2.1  OrderFillFeed interface       (no deps — do first)
2.2  Unified milestone ladder      (no deps — can parallel with 2.1)
2.3  Telegram two-way bot          (needs 2.1 for TradeFillEvent interface)
2.4  SimulatedMarketDataFeed       (no deps — can parallel)
2.5  TradeMonitor                  (needs 2.1 + 2.2)
2.6  Main + wiring                 (needs all above)
```

Steps 2.1, 2.2, and 2.4 can be done in parallel. Steps 2.3 and 2.5 follow. Step 2.6 ties them together.

---

## Phase 3: Real exit orders

**Goal:** Place actual limit/GTT exit orders on the broker at profit milestones, not just notifications.

### Step 3.1 — ExitOrderPlacer interface

| Item | Detail |
|---|---|
| New interface | `com.kbquants.session.ExitOrderPlacer` |
| Methods | `placeExitOrder(instrumentKey, qty, price)`, `cancelExitOrder(orderId)` |
| Implementations | `UpstoxExitOrderPlacer` (real), `PaperExitOrderPlacer` (log only) |
| Wiring | `TradeMonitor` takes optional `ExitOrderPlacer`; calls it on milestone AND on stop-loss hit |

### Step 3.2 — Position reconciliation

| Item | Detail |
|---|---|
| New class | `com.kbquants.live.PositionReconciler` |
| Behavior | On startup, query broker for open positions; synthesize `TradeFillEvent` for each |
| Interface | Uses a new `PositionQuery` interface (broker-agnostic) |

### Step 3.3 — ExitModel differentiation

| Item | Detail |
|---|---|
| Conservative | Tighter hard safety (15%), higher ownership locks |
| Moderate | Current defaults |
| Aggressive | Wider hard safety (25%), lower initial ownership locks |
| Config-driven | Per-model parameters from `MilestoneLadder` variants |

### Acceptance criteria (Phase 3)

- [ ] Limit/GTT sell orders placed on broker at profit milestones
- [ ] Open positions resumed on startup via reconciliation
- [ ] Conservative/Moderate/Aggressive produce measurably different exit behavior

---

## Phase 4: Production hardening

### Step 4.1 — External configuration (YAML)

All thresholds, percentages, and the milestone ladder configurable via `config.yml`.

### Step 4.2 — PHASE_4 automation

Auto-transition to PHASE_4 at configurable EOD time (e.g., 15:15 IST). Force exit all open trades.

### Step 4.3 — Observability

- Structured logging with `tradeId` in MDC
- Health checks (WebSocket state, last tick recency, token validity)
- Graceful shutdown (close feeds, log final trade states)

### Step 4.4 — Historical data feed

`HistoricalDataFeed implements MarketDataFeed` using broker's historical candle API for backtesting against real data.

### Acceptance criteria (Phase 4)

- [ ] All thresholds configurable without code changes
- [ ] Trades auto-close at EOD
- [ ] Health status queryable; logs carry trade correlation IDs
- [ ] Historical backtesting works against real past data

---

## File impact summary

| Phase | New files | Modified files |
|---|---|---|
| 2.1 | `session/OrderFillFeed` | `UpstoxOrderFillFeed`, move `TradeFillEvent`+`TradeFillListener` to `session` |
| 2.2 | `domain/MilestoneLadder` | `PhaseManager`, `MilestoneOwnershipStrategy`, `ProfitMilestoneTracker`, all their tests |
| 2.3 | `notification/TelegramCommandHandler`, `notification/TelegramBot` | — |
| 2.4 | `session/SimulatedMarketDataFeed` | — |
| 2.5 | `live/TradeMonitor` | `LiveProfitAlertRunner` (deprecated/replaced) |
| 2.6 | `Main` | `pom.xml` (maven-jar-plugin for executable jar) |
| 3 | `session/ExitOrderPlacer`, `live/UpstoxExitOrderPlacer`, `live/PositionReconciler` | `TradeMonitor` |
| 4 | `config/*` (rewrite stubs), `session/HistoricalDataFeed`, `health/HealthChecker` | `Main`, engine classes (config-driven) |

## Broker pluggability guarantee

To add a new broker (e.g., Zerodha):

1. Implement `OrderFillFeed` for their order WebSocket
2. Implement `MarketDataFeed` for their price WebSocket
3. Implement `ExitOrderPlacer` for their order API (Phase 3)
4. Wire the new implementations in `Main`

Zero changes to `TradeMonitor`, `ExitEngine`, `MilestoneLadder`, `ProfitMilestoneTracker`, `Notifier`, or any test.
