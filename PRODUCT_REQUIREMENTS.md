# xit-mc Product Requirements Document

> Version 2.0 — August 2026

## 1. Product vision

xit-mc is a **trade exit management system** for Indian equity markets. It does not decide what to buy or when to buy it — entry decisions are made by the trader. xit-mc accepts a trade invocation (the user says "I'm in this trade"), monitors the live price, and manages the entire exit lifecycle: protecting capital, locking in profit, and ultimately exiting the position according to configurable rules.

The product answers one question: **given that we are in a trade, when and how do we get out?**

## 2. Product boundaries

### In scope

- **Accepting trade invocations** from the user (via Telegram commands in MVP, via broker fill detection in live mode)
- Subscribing to live price data for active instruments
- Applying exit rules: stop-loss management, phase transitions, profit-ownership strategies
- Notifying the trader at profit milestones via Telegram
- Force-exiting a trade on user command
- Paper trading mode for end-to-end testing without a real broker
- Broker-agnostic design: plug in any broker without backend code changes
- Backtesting exit strategies against synthetic and historical price paths
- Placing limit/GTT exit orders at profit milestones (post-MVP)

### Out of scope

- Entry decisions (which stock, when, at what price) — the user makes this call
- Stock screening / scanning / discovery
- Portfolio management across multiple accounts
- Fundamental or technical analysis

### Design principles

1. **Entries are invocations, not decisions.** The user triggers "I'm buying X at Y" — the app does not care why. It just starts managing the exit.
2. **Broker-agnostic.** Every broker interaction happens behind an interface. Swap Upstox for Zerodha, or use a simulator — zero backend code changes.
3. **One milestone ladder drives everything.** Notifications, phase transitions, and ownership locks all derive from a single configurable set of thresholds.

## 3. Target users

The primary user is the developer-trader (the team itself) running this system. There is no multi-user, multi-tenant, or public-facing UI requirement at this stage.

## 4. System architecture

### Broker-agnostic interfaces

```
OrderFillFeed (interface)                MarketDataFeed (interface)
   "where fills come from"                  "where prices come from"
         |                                        |
    +---------+-----------+               +--------+-----------+
    |         |           |               |        |           |
 Upstox   Telegram    Simulated       Upstox  Simulated  Deterministic
 (live)   (manual)    (paper)         (live)  (paper)    (backtest)
```

The orchestrator (`TradeMonitor`) depends only on interfaces — it never imports a broker-specific class.

### MVP flow (Telegram + paper trading)

```
Trader                    Telegram Bot               TradeMonitor
  |                           |                          |
  |-- /buy INFY 1500 10 ---->|                          |
  |                           |-- TradeFillEvent ------>|
  |                           |                          |-- start MarketDataFeed
  |                           |                          |   (simulated or live)
  |                           |                          |
  |                           |<--- "INFY up 0.5%..." --|-- milestone crossed
  |<-- notification ----------|                          |
  |                           |                          |
  |-- /exit order-1 -------->|                          |
  |                           |-- forceExit ----------->|
  |<-- "INFY exited" --------|                          |
```

### Live flow (real broker)

```
External system places BUY on broker
        |
        v
    Broker (Upstox / any)
        |
        |--- OrderFillFeed (broker WebSocket)
        |        |
        |        v
        |   TradeMonitor (orchestrator)
        |        |
        |        | per-trade: MilestoneTracker + ExitEngine
        |        v
        |--- MarketDataFeed (broker WebSocket)
                 |
                 | price ticks
                 v
            Unified milestone ladder
              |        |         |
        Notification  Phase    Ownership
        (Telegram)  transition  lock
```

Same `TradeMonitor`, same milestone ladder, same exit engine. Only the `OrderFillFeed` and `MarketDataFeed` implementations change.

## 5. Core features

### F1. Trade invocation (entry trigger)

The app does NOT make entry decisions. It accepts an invocation: "I am now in this trade."

| Mode | How invocation happens |
|---|---|
| **Telegram (MVP)** | User sends `/buy <instrument> <price> <qty>` to the bot |
| **Live broker** | `OrderFillFeed` detects a confirmed BUY fill from the broker WebSocket |
| **Paper trading** | User sends `/buy` via Telegram; market data comes from a simulator |

All three paths produce the same `TradeFillEvent(orderId, instrumentKey, averagePrice, filledQuantity)` — the rest of the system does not know or care which path created it.

| Attribute | Detail |
|---|---|
| Output | `TradeFillEvent` |
| Dedup | Each `orderId` tracked exactly once |
| Interface | `OrderFillFeed` — broker-agnostic |

### F2. Force exit

| Mode | How it happens |
|---|---|
| **Telegram** | User sends `/exit <orderId>` or `/exit all` |
| **Automatic** | Price drops to or below current stop-loss |
| **EOD** | PHASE_4 forced close at configurable time (future) |

### F3. Live price monitoring

| Attribute | Detail |
|---|---|
| Interface | `MarketDataFeed` — broker-agnostic |
| Implementations | `UpstoxMarketDataFeed` (live), `SimulatedMarketDataFeed` (paper), `DeterministicSimulationFeed` (backtest) |
| Granularity | Per-tick (last traded price + timestamp) |
| Lifecycle | One feed started per active instrument; stops when trade exits |

### F4. Unified milestone ladder

One configurable ladder drives notifications, phase transitions, AND ownership locks. Changing the ladder in one place updates the entire system's behavior.

**Default ladder:**

| Milestone | Notification | Phase transition | Ownership lock |
|---|---|---|---|
| +0.5% | Yes | — | — |
| +1.0% | Yes | — | — |
| +2.0% | Yes | — | — |
| +3.0% | Yes | — | — |
| +5.0% | Yes | PHASE_1 → PHASE_2 | — |
| +8.0% | Yes | — | — |
| +13.0% | Yes | PHASE_2 → PHASE_3 | 30% of open profit |
| +21.0% | Yes | — | 50% of open profit |
| +34.0% | Yes | — | 70% of open profit |
| +55.0% | Yes | — | 85% of open profit |

Rules:
- Each milestone fires exactly once, ascending only (ratchet — never re-fires)
- If price jumps past multiple milestones in one tick, only the highest is reported for notification, but ALL intermediate phase transitions and ownership locks are applied
- Phase transitions and ownership locks are milestone-driven, not independent
- The ladder is configurable — change it and the whole system follows

### F5. Exit engine (core rules pipeline)

Per-tick pipeline applied to each trade:

1. **Hard safety stop** — floor stop-loss at entry price - 20%, every tick, regardless of phase
2. **Phase transitions** — driven by the milestone ladder (default: PHASE_2 at +5%, PHASE_3 at +13%)
3. **Base protection** — once in PHASE_2, ratchet stop-loss up to `basePrice`
4. **Ownership strategy** (PHASE_3 only) — lock a portion of open profit, percentage driven by the milestone ladder

All stop-loss writes enforce a **monotonic ratchet** — the stop-loss never moves backward.

### F6. Simulation and backtesting

- Synthetic price generation (stochastic model with regime bias, pullback, seeded randomness)
- Batch execution of all ExitModel x OwnershipMode combinations over a shared price path
- Metrics collection: MFE, MAE, final phase, final stop-loss, closure flags
- Reporting: console table, CSV export, JSON export

### F7. Paper trading mode

Full end-to-end flow without a real broker:
- Trade triggered via Telegram `/buy` command
- Market data from `SimulatedMarketDataFeed` (random price walks around entry price)
- Exit engine runs on every simulated tick
- Milestone notifications sent to Telegram
- Force exit via Telegram `/exit` command
- No real orders placed anywhere

Purpose: validate the entire pipeline before connecting to a real broker.

## 6. Credential management

No secrets are stored in the repository. All credentials are supplied via environment variables:

| Variable | Purpose | Required |
|---|---|---|
| `UPSTOX_API_KEY` | Upstox API key | For live mode |
| `UPSTOX_API_SECRET` | Upstox API secret | For live mode |
| `UPSTOX_REDIRECT_URI` | OAuth redirect URI | For live mode |
| `UPSTOX_ACCESS_TOKEN` | Daily OAuth token (expires nightly) | For live mode |
| `UPSTOX_SANDBOX` | Use sandbox API (`true`/`false`, default `false`) | No |
| `TELEGRAM_BOT_TOKEN` | Telegram Bot API token | Yes (MVP) |
| `TELEGRAM_CHAT_ID` | Telegram chat to receive alerts | Yes (MVP) |
| `TRADING_MODE` | `paper` or `live` (default `paper`) | No |

## 7. Non-functional requirements

| Requirement | Target |
|---|---|
| Language / runtime | Java 17 |
| Build system | Maven |
| Broker abstraction | All broker interactions behind interfaces (`OrderFillFeed`, `MarketDataFeed`) |
| Notification | Telegram Bot API (JDK HttpClient, no extra deps) |
| Concurrency | Thread-safe per-trade tracking (`ConcurrentHashMap`) |
| Resilience | Auto-reconnect on WebSocket disconnect; notification failure logged, never thrown |
| Credential storage | Environment variables only, never in repo |
| Pluggability | Swap broker by providing new interface implementations — zero orchestrator changes |
| Test coverage | All business logic unit-tested; network-dependent code tested via fakes |

## 8. Milestones and delivery phases

### Phase 0 — Research sandbox (DONE)

- Exit engine with phase transitions, stop-loss ratchet, ownership strategies
- Simulation framework with synthetic price paths
- Reporting (console, CSV, JSON)

### Phase 1 — Upstox integration (DONE)

- Upstox OAuth integration
- Order-fill detection via portfolio WebSocket
- Live price monitoring via market-data WebSocket
- Profit milestone tracking with Telegram notifications (one-way)
- End-to-end orchestration (`LiveProfitAlertRunner`)

### Phase 2 — Testable MVP (NEXT)

- **Broker abstraction**: `OrderFillFeed` interface, move `TradeFillEvent`/`TradeFillListener` to `session` package
- **Telegram as control plane**: two-way bot (receive `/buy`, `/exit`, `/status` commands; send milestone alerts)
- **Paper trading mode**: `SimulatedMarketDataFeed` + `ManualOrderFillFeed` for end-to-end testing
- **Unified milestone ladder**: merge ProfitMilestoneTracker + PhaseManager + ownership thresholds into one configurable system
- **Wire ExitEngine into live monitoring**: stop-loss + phases + ownership on every price tick
- **Force exit**: via Telegram `/exit` command

### Phase 3 — Real exit orders

- Replace/augment Telegram alerts with limit/GTT exit orders at milestones
- Position reconciliation on startup
- ExitModel behavioral differentiation (Conservative/Moderate/Aggressive)

### Phase 4 — Production hardening

- External configuration (YAML) for all thresholds
- `main()` entry point / CLI
- PHASE_4 (forced EOD exit) automation
- Historical data feed
- Structured logging, health checks, graceful shutdown

## 9. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Can't test MVP without real broker | Paper trading mode with simulated feeds — full flow works without network |
| Upstox access token expires daily | Clear documentation; future auto-refresh wrapper |
| WebSocket disconnect during trading | SDK auto-reconnect + position reconciliation on reconnect |
| Milestone/phase misalignment | Single configurable ladder drives both — impossible to drift |
| Broker lock-in | All broker interactions behind interfaces; swap implementation, not orchestration |
| Notification failure during critical move | Failures logged, never thrown — monitoring continues |
| Upstox requires a registered static IP for order-placement APIs only (SEBI algo-trading circular); changing it is rate-limited to once/week and invalidates the access token | Doesn't block Phase 0-2 (no order calls yet). Before Phase 3 (`ExitOrderPlacer`): host on infrastructure with a reserved/static public IP, register it via Upstox's `PUT /user/ip`, and treat it as a rarely-changed deployment constant, not something rotated casually |

## 10. Glossary

| Term | Meaning |
|---|---|
| **Invocation** | The act of telling xit-mc "I'm in this trade" — via Telegram command or broker fill detection |
| **Entry** | A confirmed BUY — the point at which xit-mc begins tracking a trade. NOT an entry decision. |
| **Exit** | Closing a position (stop-loss hit, profit target, or forced close) |
| **Phase** | Trade lifecycle stage (PHASE_1 through PHASE_4) |
| **Milestone** | A profit-from-entry threshold that triggers notification, phase transition, and/or ownership lock |
| **Milestone ladder** | The single ordered list of thresholds that drives the entire system |
| **Ratchet** | A value that can only increase, never decrease (stop-loss, milestone index) |
| **Hard safety** | Unconditional floor stop-loss at entry - 20% |
| **Base price** | The reference price for profit calculations |
| **Ownership** | The percentage of open profit locked as stop-loss protection |
| **Paper trading** | Full flow with simulated data — no real broker, no real orders |
| **OrderFillFeed** | Broker-agnostic interface for "where fills come from" |
| **MarketDataFeed** | Broker-agnostic interface for "where prices come from" |
| **GTT** | Good Till Triggered — a broker order type that persists until a price condition is met |
| **LTPC** | Last Traded Price and Close — the lightest Upstox market data subscription mode |
