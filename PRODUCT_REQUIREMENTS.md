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
3. **One milestone ladder drives everything.** Notifications, phase transitions, ownership locks and the hard stop all derive from a single named set of thresholds.
4. **Every number is net.** Profit means money kept after brokerage and taxes; capital is preserved only once those are covered.
5. **The app never buys.** It places sell orders for positions bought elsewhere, so every trade it manages is one the user handed it — and can take back.

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
  |-- /track INFY 1500 10 ---->|                          |
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

The system never places a buy. Every position it manages was bought elsewhere, so invocation is always an **adoption** of an existing position — `/track` declares "I am in this trade, manage the exit", it does not order anything.

| Mode | How invocation happens |
|---|---|
| **Telegram, simulated data** | `/track <instrumentKey> <price> <qty>` — everything explicit |
| **Telegram, live data** | `/track <symbol> [price] [qty]` — symbol resolved against the instrument master, price defaulted to LTP, quantity sized from capital and risk (F5a) |
| **Live broker** | `OrderFillFeed` detects a confirmed BUY fill from the broker WebSocket |

All paths produce the same `TradeFillEvent(orderId, instrumentKey, averagePrice, filledQuantity, tickSize)` — the rest of the system does not know or care which path created it.

⚠ The broker path adopts fills for the **whole account**, including positions this system was never meant to touch. Until it is opt-in (an explicit adopt step), `/pause` (F5b) is what stands between the engine and unrelated trades.

| Attribute | Detail |
|---|---|
| Output | `TradeFillEvent` |
| Dedup | Each `orderId` tracked exactly once |
| Interface | `OrderFillFeed` — broker-agnostic |

### F2. Force exit

| Mode | How it happens |
|---|---|
| **Telegram** | User sends `/exit <orderId>` or `/exit all` |
| **Automatic** | Price drops to or below current stop-loss, and the trade is `MANAGED` |
| **EOD** | PHASE_4 forced close at configurable time (future) |

Exiting closes the position. Disengaging without closing it is a different operation — see F5b.

### F3. Live price monitoring

| Attribute | Detail |
|---|---|
| Interface | `MarketDataFeed` — broker-agnostic |
| Implementations | `UpstoxMarketDataFeed` (live), `SimulatedMarketDataFeed` (paper), `DeterministicSimulationFeed` (backtest) |
| Granularity | Per-tick (last traded price + timestamp) |
| Lifecycle | One feed started per active instrument; stops when trade exits |

### F4. Named milestone sets

One ladder drives notifications, phase transitions, ownership locks AND the hard stop. The hard stop belongs to the ladder rather than being a constant, because it only makes sense alongside the rungs: 20% below entry is a disaster stop on an equity and a routine wiggle on an option premium.

**All thresholds are net profit above the cost-inclusive breakeven** (F4a), not gain from entry.

| | EQUITY | OPTIONS |
|---|---|---|
| Rungs | 1, 2, 3, 5, 8, 13, 21, 34, 55% | 1, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233% |
| PHASE_1 → PHASE_2 | +2% | +8% |
| PHASE_2 → PHASE_3 | +5% | +21% |
| Ownership locks | 5/8/13/21/34/55 → 30/50/65/75/85/90% | 21/34/55/89/144/233 → 30/50/65/75/85/90% |
| Hard stop | 20% | 40% |

Rules:
- Each milestone fires exactly once, ascending only (ratchet — never re-fires)
- If price jumps past multiple milestones in one tick, only the highest is reported for notification, but ALL intermediate phase transitions and ownership locks are applied
- Phase transitions and ownership locks are milestone-driven, not independent
- **The first ownership lock must coincide with the PHASE_3 rung.** A gap between them is a dead zone: in PHASE_3 with no applicable lock, the stop sits at breakeven while profit runs
- Sets are defined in code (`MilestoneLadder`), since the numbers encode trading intent that warrants review in a diff. **Which set is active** is a runtime choice, made via `/ladder` and applying to trades opened afterwards; open trades keep the set they were opened with

### F4a. Cost-inclusive breakeven

Capital preservation is the primary goal, so a trade's breakeven is the price at which brokerage, STT, exchange transaction charges, GST and stamp duty are all covered — **not** the entry price. `basePrice` holds that breakeven and every threshold is measured from it.

| Attribute | Detail |
|---|---|
| Source | Upstox brokerage API, one call per side; reachable with the Analytics Token and no static IP |
| Fallback | `EstimatedChargesService`, calibrated per segment, used when the call fails or in simulated mode; labelled `modelled` |
| Not a constant | Flat ₹20/order plus proportional charges means cost ranges from ~0.13% of a ₹49k equity position to ~1.55% of a ₹3.6k option one — computed per trade |
| Estimate | Sell-side charges are quoted at the entry price since the exit price is unknown; drift is ₹0.31 near breakeven, ₹89 at a +100% exit |
| Settlement | On exit, charges are recomputed at the real exit price and reported as gross / charges / net |
| Tick rounding | Breakeven is rounded **up** to a tradable tick — at a ₹1 premium a computed 1.0293 is unreachable, so the real breakeven is 1.05 |

Without this, a gross gain of 1% on a small position is reported as profit while the trade is actually losing money.

### F5. Exit engine (core rules pipeline)

Per-tick pipeline applied to each trade:

1. **Hard safety stop** — floor stop-loss at entry price − the active set's hard stop (20% EQUITY, 40% OPTIONS), every tick, regardless of phase
2. **Phase transitions** — driven by the milestone ladder, measured from `basePrice`
3. **Base protection** — once in PHASE_2, ratchet stop-loss up to `basePrice`, i.e. true breakeven
4. **Ownership strategy** (PHASE_3 only) — lock a portion of net open profit, percentage driven by the milestone ladder

All stop-loss writes enforce a **monotonic ratchet** — the stop-loss never moves backward.

### F5a. Position sizing

Quantity is derived, not typed. `CAPITAL_PER_TRADE` caps exposure; `MAX_RISK_PER_TRADE` (optional) caps the loss.

```
lots = min( capital / lotCost , maxRisk / (lotCost × hardStop%) )   capped at floor(freezeQty / lotSize)
```

- **Whole lots only.** F&O quantity must be a multiple of the contract lot (65 for NIFTY; 25–625+ across NSE). Equities fall out of the same formula with lot size 1
- **Capped, not sliced** at the exchange freeze limit (1,755 = 27 lots for NIFTY). Slicing would mean multiple fills at different prices, which the single-entry-price model cannot represent
- **Refused, not zero-sized** when capital or risk cannot cover one lot
- Risk is capped by shrinking the position, never by narrowing the stop — a stop narrow enough to look tolerable in rupees is one that ordinary noise trips

### F5b. Monitoring control

The engine can be disengaged without closing positions. This matters because the app places **only sell orders for positions bought elsewhere**: everything it manages is adopted, so the user must be able to un-adopt.

| Mode | Notifications | Stop tracked | Auto exit |
|---|---|---|---|
| `MANAGED` (default) | Yes | Yes | Yes |
| `OBSERVED` | Yes, including stop breaches | Yes | **No** |
| `RELEASED` | No | No | No |

- `/pause` and `/resume` control whether **new** trades are adopted at all; open trades stay managed
- `/release`, `/observe`, `/manage` change an open trade's mode. None of them close a position — `/exit` does that
- Releasing reports the stop being given up, since that protection disappears with it

### F6. Simulation and backtesting

- Synthetic price generation (stochastic model with regime bias, pullback, seeded randomness)
- Batch execution of every OwnershipMode over a shared price path
- Metrics collection: MFE, MAE, final phase, final stop-loss, closure flags
- Reporting: console table, CSV export, JSON export

### F7. Paper trading mode

Full end-to-end flow without a real broker:
- Trade triggered via Telegram `/track` command
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
- **Telegram as control plane**: two-way bot (receive `/track`, `/exit`, `/status` commands; send milestone alerts)
- **Paper trading mode**: `SimulatedMarketDataFeed` + `ManualOrderFillFeed` for end-to-end testing
- **Unified milestone ladder**: merge ProfitMilestoneTracker + PhaseManager + ownership thresholds into one configurable system
- **Wire ExitEngine into live monitoring**: stop-loss + phases + ownership on every price tick
- **Force exit**: via Telegram `/exit` command

### Phase 3 — Real exit orders

- Replace/augment Telegram alerts with limit/GTT exit orders at milestones
- Position reconciliation on startup

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
| **Milestone** | A net-profit threshold, measured above the cost-inclusive breakeven, that triggers notification, phase transition, and/or ownership lock |
| **Milestone ladder** | The single ordered list of thresholds that drives the entire system |
| **Ratchet** | A value that can only increase, never decrease (stop-loss, milestone index) |
| **Hard safety** | Unconditional floor stop-loss at entry − the active set's hard stop (20% EQUITY, 40% OPTIONS) |
| **Base price** | The cost-inclusive breakeven — the reference price all profit is measured from |
| **Ownership** | The percentage of open profit locked as stop-loss protection |
| **Paper trading** | Full flow with simulated data — no real broker, no real orders |
| **OrderFillFeed** | Broker-agnostic interface for "where fills come from" |
| **MarketDataFeed** | Broker-agnostic interface for "where prices come from" |
| **GTT** | Good Till Triggered — a broker order type that persists until a price condition is met |
| **LTPC** | Last Traded Price and Close — the lightest Upstox market data subscription mode |
