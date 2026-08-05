# xit-mc Product Requirements Document

> Version 1.0 — August 2026

## 1. Product vision

xit-mc is a **trade exit management system** for Indian equity markets. It does not decide what to buy or when to buy it — entries are placed by an external system or by the trader manually. xit-mc takes over the moment a BUY order is confirmed, monitors the live price, and manages the entire exit lifecycle: protecting capital, locking in profit, and ultimately exiting the position according to configurable rules.

The product answers one question: **given that we are in a trade, when and how do we get out?**

## 2. Product boundaries

### In scope

- Detecting confirmed BUY fills from the broker (Upstox) in real time
- Subscribing to live price data for filled instruments
- Applying exit rules: stop-loss management, phase transitions, profit-ownership strategies
- Notifying the trader at profit milestones (Telegram as MVP; real exit orders as the next step)
- Placing limit/GTT exit orders at profit milestones (post-MVP)
- Backtesting exit strategies against synthetic and historical price paths
- Reporting simulation results (console, CSV, JSON)

### Out of scope

- Stock screening / scanning / discovery
- Entry decisions (which stock, when, at what price)
- Placing BUY orders
- Portfolio management across multiple accounts
- Fundamental or technical analysis
- Broker integrations other than Upstox (initially)

### Design principle

Entries happen elsewhere. Exits happen here. The `TradeFillEvent` from the order WebSocket is the contract between the two systems.

## 3. Target users

The primary user is the developer-trader (the team itself) running this system alongside an external entry system. There is no multi-user, multi-tenant, or public-facing UI requirement at this stage.

## 4. System architecture

```
External entry system
        |
        | (places BUY order on Upstox)
        v
    Upstox Broker
        |
        |--- Order WebSocket (/v2/feed/portfolio-stream-feed)
        |        |
        |        v
        |   UpstoxOrderFillFeed
        |        |
        |        | TradeFillEvent (orderId, instrumentKey, avgPrice, qty)
        |        v
        |   LiveProfitAlertRunner (orchestrator)
        |        |
        |        | creates ProfitMilestoneTracker per trade
        |        | starts MarketDataFeed per instrument
        |        v
        |--- Market Data WebSocket (/v3/feed/market-data-feed)
                 |
                 | price ticks
                 v
            ProfitMilestoneTracker  -----> Notifier (Telegram / future: limit orders)
            ExitEngine (future)    -----> Order placement (future)
```

Two separate Upstox WebSocket connections, each with its own signed connection URL, both derived from the same daily OAuth access token. Market data uses binary protobuf frames; order updates use JSON strings.

## 5. Core features

### F1. Order fill detection

| Attribute | Detail |
|---|---|
| Source | Upstox portfolio-stream-feed WebSocket |
| Filter | `status == "complete"` AND `transactionType == "BUY"` (case-insensitive) |
| Output | `TradeFillEvent(orderId, instrumentKey, averagePrice, filledQuantity)` |
| Dedup | Each `orderId` tracked exactly once (guards against WebSocket re-delivery) |

### F2. Live price monitoring

| Attribute | Detail |
|---|---|
| Source | Upstox market-data-feed WebSocket (LTPC mode) |
| Granularity | Per-tick (last traded price + timestamp) |
| Lifecycle | One feed started per filled instrument; runs until trade exits |

### F3. Profit milestone alerts (MVP)

Notify the trader when profit-from-entry crosses predefined thresholds.

| Milestone | Alert |
|---|---|
| +0.5% | First profit signal |
| +1.0% | |
| +2.0% | |
| +3.0% | |
| +5.0% | |
| +8.0% | |
| +13.0% | Aligns with Phase 3 trigger |

Rules:
- Each milestone fires exactly once, ascending only (ratchet — never re-fires)
- If price jumps past multiple thresholds in one tick, only the highest is reported
- Notification failure never interrupts price monitoring

### F4. Exit engine (core rules pipeline)

Per-tick pipeline applied to each trade via `ExitEngine.onPriceUpdate(price, context)`:

1. **Hard safety stop** — floor stop-loss at entry price - 20%, every tick, regardless of phase
2. **Phase transitions**:
   - PHASE_1 to PHASE_2 when price >= entry x 1.06 (+6%)
   - PHASE_2 to PHASE_3 when price >= entry x 1.13 (+13%)
3. **Base protection** — once in PHASE_2, ratchet stop-loss up to `basePrice`
4. **Ownership strategy** (PHASE_3 only) — lock a portion of open profit as the new stop-loss:
   - *Continuous*: lock 30% of open profit continuously
   - *Milestone-based*: lock increasing % at fixed profit-from-entry thresholds:

| Profit from entry | Ownership locked |
|---|---|
| >= 13% | 30% |
| >= 21% | 50% |
| >= 34% | 70% |
| >= 55% | 85% |

All stop-loss writes enforce a **monotonic ratchet** — the stop-loss never moves backward.

### F5. Simulation and backtesting

- Synthetic price generation (stochastic model with regime bias, pullback, seeded randomness)
- Batch execution of all ExitModel x OwnershipMode combinations over a shared price path
- Metrics collection: MFE, MAE, final phase, final stop-loss, closure flags
- Reporting: console table, CSV export, JSON export

### F6. Exit order placement (post-MVP)

Replace or augment Telegram alerts with real limit/GTT exit orders at the same profit milestones. The `Notifier` interface was designed as a single-method seam (`send(String)`) specifically to make this swap straightforward.

## 6. Credential management

No secrets are stored in the repository. All credentials are supplied via environment variables:

| Variable | Purpose | Required |
|---|---|---|
| `UPSTOX_API_KEY` | Upstox API key | Yes |
| `UPSTOX_API_SECRET` | Upstox API secret | Yes |
| `UPSTOX_REDIRECT_URI` | OAuth redirect URI | Yes |
| `UPSTOX_ACCESS_TOKEN` | Daily OAuth token (expires nightly) | At runtime |
| `UPSTOX_SANDBOX` | Use sandbox API (`true`/`false`, default `false`) | No |
| `TELEGRAM_BOT_TOKEN` | Telegram Bot API token | For MVP alerts |
| `TELEGRAM_CHAT_ID` | Telegram chat to receive alerts | For MVP alerts |

The Upstox OAuth flow requires a manual browser login (password + 2FA) once per trading day. There is no supported headless/automated login.

## 7. Non-functional requirements

| Requirement | Target |
|---|---|
| Language / runtime | Java 17 |
| Build system | Maven |
| Broker | Upstox (via official Java SDK v1.27) |
| Notification | Telegram Bot API (JDK HttpClient, no extra deps) |
| Concurrency | Thread-safe per-trade tracking (`ConcurrentHashMap`) |
| Resilience | Auto-reconnect on WebSocket disconnect (SDK-level); notification failure logged but never thrown |
| Credential storage | Environment variables only, never in repo |
| Test coverage | All business logic unit-tested; network-dependent code tested via fakes/mocks |

## 8. Milestones and phases

### Phase 0 — Research sandbox (DONE)

- Exit engine with phase transitions, stop-loss ratchet, ownership strategies
- Simulation framework with synthetic price paths
- Reporting (console, CSV, JSON)

### Phase 1 — MVP live monitoring (DONE)

- Upstox OAuth integration
- Order-fill detection via portfolio WebSocket
- Live price monitoring via market-data WebSocket
- Profit milestone tracking with Telegram notifications
- End-to-end orchestration (`LiveProfitAlertRunner`)

### Phase 2 — Real exit orders (NEXT)

- Replace/augment Telegram alerts with limit/GTT exit orders at profit milestones
- Position reconciliation on startup (resume monitoring positions filled while offline)
- Wire the full ExitEngine into live monitoring (stop-loss + phase transitions + ownership)

### Phase 3 — Production hardening

- External configuration (JSON/YAML) for thresholds, percentages, credentials
- `main()` entry point / CLI
- ExitModel behavioral differentiation (Conservative/Moderate/Aggressive)
- PHASE_4 (forced EOD exit) automation
- Historical data feed (Upstox historical candle API)

### Phase 4 — Observability and operations

- Structured logging with trade-level correlation IDs
- Health checks and monitoring
- Graceful shutdown and position state persistence
- Alert escalation (multiple notification channels)

## 9. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Upstox access token expires daily | Clear documentation of manual OAuth flow; future: auto-refresh wrapper |
| WebSocket disconnect during trading hours | SDK-level auto-reconnect enabled; future: position reconciliation on reconnect |
| Missed fill event (WebSocket gap) | Position reconciliation on startup queries open positions |
| Notification failure during critical price move | Notification failures are logged, never thrown — monitoring continues regardless |
| Stop-loss never moves backward (by design) | Monotonic ratchet is a feature, not a bug — prevents whipsaw resets |
| Network-dependent code untested in dev sandbox | All code verified against real SDK bytecode; end-to-end testing required from unrestricted environment |

## 10. Glossary

| Term | Meaning |
|---|---|
| **Entry** | A confirmed BUY order fill — the point at which xit-mc begins tracking a trade |
| **Exit** | The act of closing a position (stop-loss hit, profit target, or forced close) |
| **Phase** | Trade lifecycle stage (PHASE_1 through PHASE_4) |
| **Base price** | The reference price for profit calculations (initially entry x 2.0 in simulation) |
| **Ownership** | The percentage of open profit locked as stop-loss protection |
| **Milestone** | A profit-from-entry threshold that triggers a notification or action |
| **Ratchet** | A value that can only increase, never decrease (applies to stop-loss and milestone tracking) |
| **Hard safety** | Unconditional floor stop-loss at entry - 20% |
| **LTPC** | Last Traded Price and Close — the lightest Upstox market data subscription mode |
| **GTT** | Good Till Triggered — a broker order type that persists until a price condition is met |
| **Fill** | A confirmed order execution from the broker |
