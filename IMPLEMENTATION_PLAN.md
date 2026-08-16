# xit-mc Implementation Plan

> Version 2.1 — August 2026

Phased roadmap from the current state to a production-ready exit management system. Each step lists concrete deliverables, files affected, and acceptance criteria.

## Current state

- **396/396 tests passing**, BUILD SUCCESS. `mvn package` produces a runnable fat jar.
- Exit engine (phase transitions, stop-loss ratchet, ownership strategies) — complete
- Simulation framework (price generation, batch execution, reporting) — complete
- Upstox implementation (OAuth, market data WebSocket, order fill WebSocket, quotes, charges) — complete and broker-agnostic-compatible
- **Phase 2 (Testable MVP) is complete.** Paper trading works end-to-end via Telegram.

Built since, all on the read-only Analytics Token (no daily login, no static IP):

| | |
|---|---|
| **Live market data** | `MARKET_DATA=live` streams real ticks into the exit engine while trades stay on paper |
| **Symbol resolution** | `/track nifty25000ce18aug26` — instrument master cached weekly, case- and space-insensitive, local map lookup |
| **Sizing** | Whole lots, capped at the exchange freeze limit, from `CAPITAL_PER_TRADE` and optionally `MAX_RISK_PER_TRADE` |
| **Net-of-cost thresholds** | `basePrice` is the cost-inclusive breakeven; charges come from Upstox's brokerage API and are settled at the real exit price |
| **Named milestone sets** | EQUITY and OPTIONS, selectable at runtime via `/ladder`, each carrying its own hard stop |
| **Monitoring control** | `/pause`, `/release`, `/observe` — disengage without closing positions |

**Remaining gaps going into Phase 3:** *(struck through as Phase 3 delivers them)*

- ~~The order side is not wired into `Main`~~ — `WATCH_BROKER_FILLS` wires `UpstoxOrderFillFeed`, paired with explicit adoption
- ~~No real exit order placement~~ — `UpstoxExitOrderPlacer`, behind `PLACE_REAL_ORDERS`
- ~~No position reconciliation~~ — `/positions`, and automatically once a `/token` arrives
- ~~Adoption becomes opt-out the moment the fill feed is wired~~ — it didn't: the feed only ever ships with `requireExplicitAdoption`, and the two are set together in `Main` so neither can be enabled alone
- Ladder numbers remain unvalidated against data, and the simulation layer cannot validate them (GBM on the instrument vs. an option premium's convexity plus decay)

See DEVELOPMENT.md §9 for the full gap list.

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
| `/track <instrument> <price> <qty>` | Create a `TradeFillEvent` and start monitoring |
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

For the order fill side, the Telegram `/track` command (step 2.3) IS the fill source in paper mode — no separate `SimulatedOrderFillFeed` needed. The `TelegramCommandHandler` directly creates a `TradeFillEvent` and passes it to the orchestrator.

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
| Result | `java -jar xit-mc.jar` → bot is listening → user sends `/track INFY 1500 10` → simulated prices flow → milestone alerts → user sends `/exit` → done |

### Acceptance criteria (Phase 2 complete when all are true)

- [x] `OrderFillFeed` interface exists; `UpstoxOrderFillFeed` implements it; orchestrator takes the interface
- [x] One `MilestoneLadder` configures notifications, phase transitions, AND ownership locks
- [x] Phase triggers are at milestone values (5% and 13%), not independent hardcoded values
- [x] Telegram bot accepts `/track`, `/exit`, `/status` commands
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

### Step 3.1 — ExitOrderPlacer interface ✅

| Item | Detail |
|---|---|
| Interface | `com.kbquants.session.ExitOrderPlacer` |
| Method | `placeExit(fill, price, reason)` returning placed / simulated / failed / **uncertain** |
| Implementations | `UpstoxExitOrderPlacer` (real, MARKET SELL), `PaperExitOrderPlacer` (log only) |
| Wiring | `TradeMonitor` routes every exit through it; `PLACE_REAL_ORDERS` picks the real one |

Departures from the sketch above, both forced by real money:

- **No `cancelExitOrder`.** Exits are MARKET, so there is nothing resting to cancel. A limit that doesn't fill leaves the position open with the stop already breached — the wrong failure for an engine whose job is to be out.
- **A fourth outcome, `uncertain`.** A timeout is not a rejection: the order may be resting at the exchange. Retrying it would open a short. Uncertain stops the engine acting and hands the trade back as `OBSERVED`.

### Step 3.1b — Broker fill feed with explicit adoption ✅

| Item | Detail |
|---|---|
| Env | `WATCH_BROKER_FILLS` (default off) |
| Refactor | `UpstoxOrderFillFeed` takes a `TradingToken`, waits when there is none, connects on `onCredentialAvailable()` |
| Behaviour | A detected fill is **offered**, not adopted: two buttons, or `/adopt` |
| Commands | `/adopt` (list), `/adopt <orderId>`, callbacks `adopt:` / `ignore:` |

The stream carries fills for the whole account, so the feed and the adoption gate are set together in `Main` and neither can be enabled without the other. Declined fills are remembered, since a reconnect replays them. Adopting while paused is refused *and the offer kept* — consuming it would lose a real position.

**Prerequisite before this step goes live:** Upstox requires order-placement API calls to originate from a **registered static IP** (SEBI algo-trading circular; scoped to order APIs only — market data/portfolio feeds and read-only APIs are unaffected, so nothing before this step needs it). Register the deployment host's static IP via `PUT /user/ip` before the first live `UpstoxExitOrderPlacer` call. Two operational gotchas: the IP can only be changed **once per calendar week**, and each change **invalidates the current access token** — so this isn't something to rotate casually; pick the production host's IP deliberately.

### Step 3.2 — Position reconciliation ✅

| Item | Detail |
|---|---|
| Interface | `com.kbquants.session.PositionQuery` — **throws** rather than returning an empty list when it cannot ask |
| Comparison | `com.kbquants.session.PositionReconciliation` — pure and static, matched by instrument |
| Implementations | `UpstoxPositionQuery` (short-term positions), `NoOpPositionQuery` (never available) |
| Trigger | `/positions`, and automatically on `/token` when trades are open |

Departures from the sketch above:

- **Not on startup.** Reconciliation needs the daily token, and at startup there is none — it arrives via `/token` mid-session. So the automatic run hangs off the token arriving, on its own thread so the command loop keeps answering.
- **No synthesised trade for every position.** A position this system isn't watching is *offered*, through the same adoption path as a detected fill. Silently taking on whatever the account holds is the failure mode step 3.1b exists to prevent.
- **A discrepancy stands a trade down; it never drops it.** Acting on a stale position is what causes harm — an exit sized from a position that has shrunk sells quantity that isn't there, and selling past flat opens a short. But the broker can be the one that's wrong (a delivery holding, which lives on a different endpoint), and standing down is recoverable where dropping is not.
- **The empty-list trap is the design constraint.** "Nothing is open" and "I couldn't ask" are the same value in a `List`, and confusing them would stand down every managed trade at once. Hence a checked `PositionQueryException`.

### Step 3.3 — ~~ExitModel differentiation~~ (superseded)

Delivered as **named milestone sets** instead. What this step described —
per-model hard stops and ownership ladders, driven by `MilestoneLadder`
variants — is what `MilestoneSets` now does, selectable at runtime via
`/ladder`. `ExitModel` itself was removed: it never branched behaviour,
and keeping a second axis for the same idea would have meant two ways to
express one decision.

Adding a set is adding a `MilestoneLadder` factory; nothing else is needed.

### Acceptance criteria (Phase 3)

- [x] Real sell orders placed on the broker, behind `PLACE_REAL_ORDERS`, with a duplicate guard and an explicit unknown outcome
- [x] Positions opened at the broker are detected and offered for adoption
- [x] Managed trades checked against the broker's actual positions, and stood down when they disagree
- [x] ~~Conservative/Moderate/Aggressive~~ → named milestone sets produce measurably different exit behaviour (step 3.3)

---

## Phase 3.5: Multi-account support

**Goal:** Up to 10 traders, each with their own Upstox account and Telegram chat, run on the same process with zero cross-visibility. Every account's trades, stop-losses, risk settings and milestone-set choice are isolated from every other account's.

This phase exists because the product target changed from "one developer-trader" to "up to 10 independent traders" (see PRODUCT_REQUIREMENTS.md §3, F8). It sits before Phase 4 because production hardening (static IP, real-order verification) is only worth doing once against the shape the system will actually run in — verifying single-account behavior and then re-verifying after a multi-account rewrite would duplicate the riskiest work.

**Why this is tractable in one phase:** `TradeMonitor`, `ExitEngine`, `OrderFillFeed`, `MarketDataFeed`, and `ExitOrderPlacer` are already broker-agnostic interfaces with no global/static state (CODING_STANDARDS.md §3, §6). Isolation is achieved by constructing **one full set of these per account**, not by adding account-awareness inside the engine. `ExitEngine`, `PhaseManager`, `StopLossEngine`, and the ownership strategies need zero changes.

### Step 3.5.1 — Account registry

| Item | Detail |
|---|---|
| New class | `com.kbquants.domain.TraderAccount` — immutable: `accountId`, `telegramChatId`, Upstox credentials, `capitalPerTrade`, `maxRiskPerTrade`, default milestone set |
| New class | `com.kbquants.session.AccountRegistry` — loads all configured accounts at startup, indexes by `accountId` and by `telegramChatId` |
| Storage | A single gitignored config file (e.g. `accounts.yml`, alongside the existing gitignored `run.sh`/`run.ps1` pattern) — 10 known accounts don't warrant a database. One entry per trader, same fields as today's per-process env vars |
| Validation | Fail fast at startup on a malformed or duplicate `telegramChatId`/`accountId` — better to refuse to start than to silently misroute one trader's trades to another |

**Tests:** `AccountRegistryTest` — load valid config, reject duplicate chat ids, reject duplicate account ids, lookup by either key, missing-file behavior.

### Step 3.5.2 + 3.5.3 — Per-account TradeMonitor and chat-id routing (shipped together)

**Delivered as one PR, not two.** `TelegramCommandHandler` was 1:1 with a single `TelegramCommandListener` — until it can resolve which account a chat id belongs to, it has no way to reach more than one `TradeMonitor` at all. Landing 3.5.2 alone would have meant `Main` built N monitors that nothing could ever route a command to: a broken intermediate state, not a working reduced one. See CODING_STANDARDS.md's testing philosophy and the project's "no half-finished implementations" rule.

| Item | Detail |
|---|---|
| Refactor | `Main` builds `Map<accountId, TradeMonitor>` (and a parallel `Map<telegramChatId, TradeMonitor>` for routing) from the registry instead of one process-wide `TradeMonitor` |
| Construction | Each `TradeMonitor` gets its own `OrderFillFeed`, `MarketDataFeed` factory, `ExitOrderPlacer`, `Notifier` (bound to that account's chat id), `TradingToken`, `TradeStore`, and `MilestoneLadder`/risk settings sized from that account's own `capitalPerTrade`/`maxRiskPerTrade` — the same constructor `TradeMonitor` already took, just called N times instead of once |
| Shared, deliberately | The instrument master (`InstrumentCatalog`) is built once and passed to every account's resolver — it's public market data, not a credential, and downloading it N times would buy nothing |
| Isolation guarantee | No shared mutable state between `TradeMonitor` instances — each owns its own `TradeContext` map, per CODING_STANDARDS.md §6 |
| Startup failure policy | A misconfigured or invalid account (e.g. an unresolvable `defaultMilestoneSetName` — belt-and-suspenders since `AccountRegistry` already validates it) fails the whole process at startup, the same way `AccountRegistry.load` already fails the whole registry on one bad entry, rather than silently starting with fewer accounts than configured. Continuing with only 9 of 10 accounts is not a safe degradation for a system whose job is watching money — a trader who believes their account is live and managed, when it silently isn't, is worse than the process refusing to start. This supersedes the original plan's "fault isolation... never let one bad registration abort the whole process" wording, which did not yet account for `AccountRegistry`'s own already-fail-fast behavior |
| `TelegramCommandHandler` refactor | Constructor changed from `(TelegramCredentials, TelegramCommandListener)` to `(String botToken, Function<String, Optional<TelegramCommandListener>> listenerByChatId)`. Every update's chat id is read from Telegram's `message.chat.id` (or, for a button tap, `callback_query.message.chat.id`) and resolved against the registry-derived map before any dispatch happens |
| Behavior | Recognised chat id → dispatch to that account's `TradeMonitor`. Unrecognised chat id → fixed `UNKNOWN_ACCOUNT_REPLY`, nothing else (no account enumeration, no hint about who *is* registered). A `/token` message is still deleted from chat history regardless of registration, since the credential-exposure risk doesn't depend on whether the sender turns out to be a known account |
| Outbound | `TelegramNotifier` for a given `TradeMonitor` sends only to that account's `telegramChatId`, never the others |

**Tests:** `TelegramCommandHandlerTest` gained `chatIdOf` unit tests (chat present / chat null) and constructor validation tests (blank/null bot token, null resolver). `AccountRegistry`/`TraderAccount` tests from 3.5.1 already cover the data layer this reads from. `Main`'s wiring itself follows the project's existing convention of not being unit-tested (thin `main()`; manually verified) — see DEVELOPMENT.md §7's note on the single-account `Main`, which the same rule now extends to.

### Step 3.5.4 — Per-account persistence (DONE)

| Item | Detail | Status |
|---|---|---|
| Refactor | `Main` passes `new JsonTradeStore(path)` with a per-account path, `<TRADE_STATE_DIR>/<accountId>.json` (default dir `~/.xit-mc/open-trades/`) — `JsonTradeStore` itself needed no change, its `Path`-accepting constructor already existed | Shipped |
| Restart behavior | Every account's open trades restore to that account only, since each has a distinct file | Shipped |
| Corrupt-file isolation | A corrupt file for one account must not block another account's trades from loading | Shipped — `corruptFileForOneAccountShouldNotAffectAnothersInTheSameDirectory` in `TradeMonitorPersistenceTest`: two `JsonTradeStore`s in one temp directory, one file corrupted, confirms the other's valid trade still loads untouched. Mostly proved the negative (no static cache or shared handle links two instances) since `JsonTradeStore.load()`'s existing per-file try/catch already gave this for free |

### Step 3.5.5 — Per-account daily order token

No new code beyond 3.5.3: once `/token` is routed by chat id, it is inherently per-account. Each trader supplies their own daily Upstox token in their own chat; `PLACE_REAL_ORDERS` and reconciliation already read whichever token is attached to the account making the call.

### Step 3.5.6 — Static IP: shared host, per-account registration (operational, not code)

One production host and one static IP serve all 10 accounts — Upstox's rule is about where the order-placement HTTP call originates, not which account it's for. But each of the 10 traders must **individually** register that same IP against their own Upstox developer app (`PUT /user/ip`, using their own token). This is a one-time step per trader to do during onboarding, not a code change.

**Status (August 2026): not started.** No static IP or VM has been registered yet — see Phase 4's note below. This step, and the Phase 4 items that depend on it, are deliberately deferred rather than worked on speculatively without the infrastructure to verify against.

### Step 3.5.7 — NIFTY-only instrument scope + daily strike window

**Not originally planned as part of Phase 3.5** — added once it became clear the general-purpose instrument master (80k+ NSE instruments, all equities/indices/F&O) was fetching and holding far more than this MVP's actual scope needs. Same cut as the rest of this project's direction: keep only what's needed right now, not what a general platform might eventually want.

| Item | Detail |
|---|---|
| Refactor | `InstrumentMasterLoader` filters at parse time to `segment=NSE_FO`, `instrument_type` in `{CE,PE}` (drops futures), underlying `name="NIFTY"` (drops every other underlying, and equities/indices entirely) |
| New fields | `Instrument` gains `strikePrice` and `expiry` (option-only; 0/null elsewhere). `expiry`/`strike_price` field names are **unverified against a live Upstox payload** (outbound access to upstox.com is blocked in this sandbox) — flagged in code per CODING_STANDARDS.md §11; if wrong, the parser keeps zero contracts rather than wrong ones, which is loud (an empty catalog) not silent |
| Refresh cadence | Daily instead of weekly (`InstrumentMasterLoader`'s cache is now stamped by calendar day, not the most-recent-Wednesday anchor). Affordable now that the retained dataset is tiny; removes the old "contract listed since last refresh doesn't resolve" staleness |
| New class | `StrikeWindow` — pure computation, no I/O: nearest expiry on/after today, then 15 rungs each side of the strike nearest spot (`InstrumentCatalog.RUNGS_EACH_SIDE`), clamped at the edges of the actually-listed chain |
| Refactor | `InstrumentCatalog.loadFrom`/`refresh` now take a `QuoteService`, fetch NIFTY spot (`NSE_INDEX|Nifty 50`), and narrow the raw chain through `StrikeWindow` before exposing it — callers never see the wider chain. Spot unavailable or no upcoming expiry → empty registry on initial load, previous registry kept on refresh (same "stale beats none" pattern as the rest of this class) |
| New schedule | `DailyWallClockSchedule` (renamed from `EndOfDaySchedule`, which was already fully generic internally — reused rather than duplicated) drives a morning refresh, `INSTRUMENT_REFRESH_TIME` (default 08:45 IST), ahead of NSE F&O's 09:15 open |
| Shared credential | The one spot-price lookup uses the first registered account's Analytics Token — arbitrary but harmless, since any valid one reads the same public index quote |

**Tests:** `InstrumentMasterLoaderTest` rewritten for daily cache-file naming + parse-level NIFTY/CE-PE/futures/equity filtering. New `StrikeWindowTest` (nearest-expiry and strike-selection, including edge clamping). `InstrumentCatalogTest` rewritten for spot-driven windowing, including both refresh-failure modes (download fails vs. spot unavailable). `InstrumentRegistry` gained `all()` with a test. `DailyWallClockScheduleTest` renamed, mechanically unchanged.

**Acceptance criteria:**

- [x] `/track` resolves NIFTY 50 options within today's window; everything else (other underlyings, futures, equities, out-of-window strikes) resolves as `unknown instrument` — no separate rejection path needed, since it's simply not in the catalog
- [x] Instrument master parse keeps only NIFTY CE/PE records — verified by `InstrumentMasterLoaderTest`'s filtering tests
- [x] Strike window is 15 rungs each side of ATM, clamped at chain edges — verified by `StrikeWindowTest`
- [x] A failed spot lookup or download on refresh keeps the previous window rather than clearing it
- [ ] Field names (`strike_price`, `expiry`) verified against a real Upstox payload — blocked on the same static-IP/network-access gap as Phase 4; revisit together
- [x] Known gap closed: `TradeMonitor.onDetectedFill` now checks a detected fill's instrument key against today's NIFTY window (`isOutOfScope`) before offering, adopting, or auto-adopting it — a detected non-NIFTY position is silently skipped (logged, not notified) rather than reaching `onFill`. See PRODUCT_REQUIREMENTS.md F9

### Acceptance criteria (Phase 3.5: all true — phase complete)

- [x] 2+ accounts configured with distinct chat ids and credentials, running trades simultaneously — mechanically true given per-account `TradeMonitor` construction; not yet exercised end-to-end against real Telegram/Upstox (same network-untestable caveat as the rest of `live`/`notification`)
- [x] A command sent from account A's chat never reads or changes account B's trades — chat id resolved to an account before any dispatch; covered by `chatIdOf` + constructor tests, not yet by a full two-account integration test
- [x] Each account's persistence file round-trips independently across a restart — distinct path per account
- [x] Corrupt-file isolation between accounts' persistence has a dedicated test — `corruptFileForOneAccountShouldNotAffectAnothersInTheSameDirectory` (step 3.5.4)
- [x] ~~A malformed or failing account... does not prevent other accounts from running~~ — superseded: the deliberate policy is now fail-fast for the whole process on any misconfigured account (see step 3.5.2+3.5.3's "Startup failure policy" row)
- [x] Adding an 11th account is a config-only change — no code, no redeploy of logic
- [x] All existing single-account tests still pass — 445/445 (396 pre-3.5 + 21 from 3.5.1 + 6 from 3.5.2/3.5.3 + 21 from 3.5.7 + 1 from the corrupt-file-isolation test)
- [x] Instrument scope narrowed to NIFTY 50 options only, daily-refreshed strike window (step 3.5.7)

**Phase 3.5 is done.** Remaining open items are tracked individually, not blocking: field-name verification against a live Upstox payload (step 3.5.7), and the broker-fill-adoption instrument-scope gap (PRODUCT_REQUIREMENTS.md F9) — both revisit once there's network access to a real Upstox endpoint. Next up: Phase 4, per its own status note above (kb-test merge and external config are unblocked now; real-order verification stays deferred).

---

## Phase 4: Production hardening

**Status (August 2026): the real-order-verification work is blocked.** No static IP or VM has been registered yet, so nothing that requires placing a real order or connecting from a fixed address (Upstox's `PUT /user/ip`, the first supervised `PLACE_REAL_ORDERS` trade) can be attempted or verified right now — see step 3.5.6. That is not a reason to stall the rest of this phase: step 4.0 (the `kb-test` merge) and step 4.1 (external configuration) have no static-IP dependency and can proceed independently. Revisit the blocked items once the infrastructure exists rather than working on them speculatively without anything to verify against.

### Step 4.0 — Merge kb-test into this repo (history-preserving) — DONE

**Status: complete except 4.0.k (archiving kb-test on GitHub, a manual repo-owner action).** Landed via story #20. Two sub-steps resolved differently than originally planned — see the notes on 4.0.e and 4.0.i below; both differences are intentional and are recorded in `REPO_STRATEGY.md`'s "Merge record" section.

**Goal:** collapse the two-repo split into a single source of truth, so future work has one place to be. Timed to sit first in Phase 4 because Phase 4.4 (historical backtest feed) needs `kb-test`'s `market-data-engine` code — pulling it in as a separate merge lets 4.4 be a wiring change rather than a wiring-plus-import change.

See `REPO_STRATEGY.md` for the shape decisions this step executes. Recap: `market-data-engine/` (code) and `docs/` (architectural docs for the future signal-generation products) are both imported; empty module skeletons and the parent multi-module POM are dropped; `AGENTS.md` is folded into `CODING_STANDARDS.md` where relevant and otherwise dropped; `kb-test` is archived on GitHub afterwards, not deleted.

| Sub-step | Detail |
|---|---|
| **4.0.a** — filter kb-test (code) | Fresh throwaway clone of `kb-test`; `git filter-repo --path market-data-engine/ --path docs/` to rewrite it down to just the module's code + the docs directory. Empty module directories, `AGENTS.md`, `README.md`, and the parent `pom.xml` are dropped from the rewritten history. |
| **4.0.b** — path-rewrite for target layout | In the same filter-repo pass: `market-data-engine/src/**` → `src/**` (files keep their `com.kbquants.marketdata.*` package during the merge; the interface-collision refactor in 4.0.e is when packages actually change); `docs/**` → `docs/future-products/**`. |
| **4.0.c** — pom reconciliation preflight | On a scratch branch of `kb-xit`, bump Lombok to `1.18.42` and JUnit to `5.10.2`, add `jackson-databind` (needed by the Upstox candle parser), and run `mvn test` — all **396 existing tests must still pass** before proceeding. `slf4j-api` is already effectively present via Upstox SDK's transitive tree; add explicitly only if the ported code's `LoggerFactory` calls fail to resolve. |
| **4.0.d** — merge with unrelated histories | `git remote add kb-test-filtered <path>` and `git merge --allow-unrelated-histories kb-test-filtered/main` (the filtered clone's default branch — earlier drafts of this plan named a `mvp1.0/market-data-engine` branch that didn't end up existing). Zero tree-level conflicts, as expected: `kb-xit` had no `market-data-engine/` or `docs/` directories to collide with. |
| **4.0.e** — reconcile the `MarketDataFeed` collision (DONE, differently than planned) | Forcing the ported pull-based historical-candle interface to implement `kb-xit`'s push-based `session.MarketDataFeed` (`start(PriceListener)`, live ticks) turned out to be a poor fit once the code was in front of us — different problem shapes. Instead the ported interface was kept separate and renamed away from the name clash: `com.kbquants.marketdata.feed.MarketDataFeed` → `HistoricalCandleFeed`. Only one interface named `MarketDataFeed` exists in `kb-xit` either way, which is what the acceptance criterion actually needs. |
| **4.0.f** — rename the `UpstoxMarketDataFeed` collision | Done as planned: the ported class became `UpstoxHistoricalCandleFeed`; the existing live-tick `UpstoxMarketDataFeed` keeps its name. The 17 ported tests needed no changes (none referenced the class by name). |
| **4.0.g** — retire AGENTS.md | Do not carry `kb-test`'s `AGENTS.md` across. Read it once during the merge and fold anything this codebase actually wants to enforce (e.g. registry-pattern extensibility, event-driven communication *for future modules*) into `CODING_STANDARDS.md`, scoped to where it applies. Everything left over — the 90%-coverage mandate, the "no if/switch on type" repo-wide rule, the exit-engine-specific invariants that already live in `CODING_STANDARDS.md` in a lighter form — stays only in the archived `kb-test`. |
| **4.0.h** — label docs/future-products/ | Add a `docs/future-products/README.md` making clear these docs describe **planned** signal-generation products (indicators, strategies, entry engine, scanner, orchestrator) that are **not yet built in this repo** — they're reference material for when that work starts, not documentation of code that exists today. Without this label, a reader lands in `docs/future-products/16_MARKET_DATA_ENGINE.md` and reasonably assumes it describes what's shipping. |
| **4.0.i** — verify (DONE, count updated) | `mvn test` shows **467 tests passing, zero failures** (450 kb-xit + 17 ported — the plan's "396 + 17 = 413" figure was stale by the time this step ran; `kb-xit` had grown to 450 tests since the plan was written). |
| **4.0.j** — update planning docs | Mark `REPO_STRATEGY.md` as "merged" instead of "scheduled"; update `DEVELOPMENT.md` §2 to include the new package; update `PRODUCT_REQUIREMENTS.md`'s phase table (Phase 4); add `docs/future-products/` to `README.md`'s "Project documentation" table. |
| **4.0.k** — archive kb-test on GitHub | Manual action by the repo owner via GitHub settings — not a code change. Its README already points at `kb-xit` (pushed on the planning branch alongside this plan). |

**Acceptance criteria (step 4.0 complete when all are true):**

- [x] `git log --follow` on any file that came from `kb-test`'s `market-data-engine` OR `docs/` shows the original commits, authors, and dates back to that repo's history
- [x] `mvn test` in `kb-xit` reports 467 tests passing (450 kb-xit + 17 ported)
- [x] Exactly one `MarketDataFeed` interface remains in `kb-xit` (`com.kbquants.session.MarketDataFeed`)
- [x] Exactly one class named `UpstoxMarketDataFeed` remains, and it's the live-tick one; the historical implementation is named distinctly (`UpstoxHistoricalCandleFeed`)
- [x] `AGENTS.md` does not exist in `kb-xit`; anything worth keeping from it lives in `CODING_STANDARDS.md`
- [x] `docs/future-products/README.md` clearly frames those docs as forward-looking, and no other doc in `kb-xit` references them as if they describe current code
- [x] No empty module directories anywhere in `kb-xit` (no `indicators-engine/`, `entry-engine/`, etc. carried across as bare `pom.xml`s)
- [ ] `kb-test` is archived (read-only) on GitHub — pending repo-owner action (subtask #36)

**Rollback:** the merge is a single commit; if anything downstream goes wrong `git revert` on that merge reinstates the pre-merge state without touching the filter-repo scratch clone. `kb-test` on GitHub is not touched until 4.0.j, so it can always be re-cloned and the merge re-attempted.

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
