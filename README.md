# xit-mc

A trade exit management system: you tell it you're in a trade, it watches the live price and manages the exit — stop-loss, phase transitions, and profit-ownership locking — via a deterministic rules engine. It does not decide what to buy or when. See [PRODUCT_REQUIREMENTS.md](PRODUCT_REQUIREMENTS.md) for the full scope.

**It never places a buy order.** Every position it manages was bought elsewhere and handed to it — so it can also be handed back, without closing anything.

Right now the runnable mode is **paper trading**, on either a simulated price feed or **real Upstox market data**. You declare a trade via Telegram, the engine watches the price and manages the exit, and every figure it reports is **net of brokerage and taxes**. Real sell-order placement is on the roadmap; see [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).

## Prerequisites

- Java 17
- Maven
- A Telegram bot (a token + your chat id) — see [Setting up your Telegram bot](#setting-up-your-telegram-bot) below if you don't have one yet

## Quick start (paper trading)

```bash
git clone https://github.com/YaappsTeam/kb-xit.git
cd kb-xit
```

Pick the path for your shell:

**macOS / Linux / Git Bash — `run.sh`:**

```bash
cp run.sh.example run.sh
```

Edit `run.sh` and fill in your real values:

```bash
export TELEGRAM_BOT_TOKEN="your-telegram-bot-token-here"
export TELEGRAM_CHAT_ID="your-telegram-chat-id-here"
```

Build and run:

```bash
mvn package
./run.sh
```

**Windows PowerShell — `run.ps1`:**

Bash scripts don't run in PowerShell, and double-clicking `run.sh` in Explorer/IntelliJ just opens Windows' "pick an app" dialog — it never executes it. Use the PowerShell equivalent instead:

```powershell
Copy-Item run.ps1.example run.ps1
```

Edit `run.ps1` and fill in your real values:

```powershell
$env:TELEGRAM_BOT_TOKEN = "your-telegram-bot-token-here"
$env:TELEGRAM_CHAT_ID = "your-telegram-chat-id-here"
```

Build and run:

```powershell
mvn package
./run.ps1
```

---

Either way, `run.sh` / `run.ps1` are already gitignored — your token never risks being committed. Never edit `run.sh.example` / `run.ps1.example` with real values; those files *are* tracked.

You should see:

```
xit-mc started in PAPER trading mode. Send /track <symbol> to Telegram to begin.
```

Now message your bot on Telegram:

```
/track NSE_EQ|INE848E01016 1500 10
```

A simulated price feed starts at ₹1500 and random-walks from there. You will first be told when the trade clears breakeven (brokerage and taxes covered), then as it crosses each net profit milestone (1%, 2%, 3%, 5%, 8%, 13%, 21%, 34%, 55%); if it drops to the stop-loss, the trade auto-closes and you're notified. Send `/status` anytime to see open trades, or `/exit <orderId>` / `/exit all` to close manually.

Stop the process with `Ctrl+C` — it shuts down the Telegram poller cleanly.

## Live market data (paper trades, real prices)

Set `MARKET_DATA=live` to drive the exit engine from real Upstox ticks instead of the simulated random walk. Trades stay on paper — nothing places a broker order.

This needs an Upstox **Analytics Token**, which is not the same as the standard access token:

| | Analytics Token | Standard access token |
|---|---|---|
| Validity | **1 year** | Until 3:30 AM the next day |
| How to get it | Developer Apps → **Analytics** tab → *Generate Token*. No OAuth redirect, no 2FA | Interactive authorization-code login, every trading day |
| Market data + WebSocket streaming | ✅ (no static IP needed) | ✅ |
| Placing/modifying orders | ❌ read-only | ✅ |

Live prices only ever need the first one — so live-data mode involves **no daily login**, and the token it uses physically cannot place an order. The daily token and the [static-IP registration](PRODUCT_REQUIREMENTS.md) come in later, with live order placement (Phase 3).

Add to your `run.ps1` / `run.sh`:

```powershell
$env:MARKET_DATA = "live"
$env:UPSTOX_ANALYTICS_TOKEN = "your-upstox-analytics-token-here"
```

```bash
export MARKET_DATA="live"
export UPSTOX_ANALYTICS_TOKEN="your-upstox-analytics-token-here"
```

You should see `... started in PAPER trading mode with LIVE Upstox market data`. Live mode also needs `CAPITAL_PER_TRADE` — see the next section, which covers how `/track` is used from here on.

Ticks only arrive while the market is open, so outside market hours the engine sits idle rather than reporting an error.

## Symbols, prices and lot sizing (live mode)

Typing `NSE_FO|45148` while scalping is not realistic, so in live mode `/track` takes a **trading symbol** and fills in the rest:

```
/track nifty25000ce18aug26        price = LTP, quantity = capital ÷ lot cost
/track ACC 25                     quantity 25, price = LTP
/track ACC 1850.5 25              both explicit
/track NSE_EQ|INE012A01025 …      raw instrument key still works
```

Matching ignores case and spaces, so `NIFTY 25000 CE 18 AUG 26` and `nifty25000ce18aug26` are the same instrument, and `NIFTY50` finds the index. Symbols are looked up in a local copy of Upstox's instrument master (~2 MB, ~43k instruments, cached in `~/.xit-mc/instruments`) — a map lookup, not an API call, so it costs nothing in the hot path. With exactly one trailing number it is read as a **quantity**, never a price.

**Quantity is sized in whole lots**, which matters for F&O where quantity must be a multiple of the contract lot:

```
lots = floor(CAPITAL_PER_TRADE / (ltp × lotSize))     capped at floor(freezeQty / lotSize)
```

Equities have `lotSize = 1`, so the same formula gives plain capital ÷ price. Worked examples at `CAPITAL_PER_TRADE=50000`:

| Command | LTP | Lot | Result |
|---|---|---|---|
| `ACC` | 1,362.80 | 1 | 36 → ₹49,061 |
| `nifty25000ce18aug26` | 55.30 | 65 | 13 lots = 845 → ₹46,729 |
| `nifty25000pe18aug26` | 431.45 | 65 | 1 lot = 65 → ₹28,044 |

Two deliberate behaviours:

- **Capped, not sliced.** Exchanges reject single orders above a freeze quantity (1,755 for NIFTY, i.e. 27 lots). When your capital would buy more — routine on expiry days — the order is capped at that limit rather than split into several. Slicing is a planned enhancement; multiple fills at different prices don't fit the engine's single-entry-price model yet.
- **Refused, not zero-sized.** If capital won't cover even one lot, `/track` reports why instead of opening a zero-quantity trade.

Indices are streamable but **cannot be bought** — `/track NIFTY50` is refused, since a position exists only in the corresponding option or future.

A handful of trading symbols are ambiguous (`CHOLAFIN`, `MOTHERSON`, `ELECTCAST`, `IMC1`, `SILVER`). Rather than guess, `/track` lists the candidates and asks for the full instrument key.

In simulated mode there is no instrument master and no price source, so `/track` still requires `<instrumentKey> <price> <qty>` in full.

### Instrument master refresh

The master refreshes **weekly, on Wednesdays** — the cache is stamped with the most recent Wednesday, so the first run on or after one downloads and every run until the next reads from disk. That keeps the ~2 MB download and 37 MB parse off the VM on the other six days.

The trade-off: between refreshes, contracts listed since Wednesday won't resolve, and expired ones linger. When you need one immediately:

```
/refresh
```

That forces a download regardless of schedule and takes effect on the next command. If it fails, the previous master stays loaded — stale beats none mid-session.

## Costs and breakeven

The primary goal is capital preservation, so **every percentage is net of costs**. A trade's breakeven is not its entry price — it's the price at which brokerage, STT, exchange transaction charges, GST and stamp duty are all covered.

Costs come from Upstox's own brokerage calculator (reachable with the Analytics Token, no static IP), one call per side at `/track`. Real quotes:

| Position | Invested | Round trip | % of invested |
|---|---|---|---|
| NIFTY 25000 CE, 13 lots | ₹46,728 | ₹157.87 | 0.338% |
| NIFTY 25000 CE, **1 lot** | ₹3,594 | ₹55.72 | **1.550%** |
| ACC equity, 36 sh | ₹49,061 | ₹64.62 | 0.132% |

**The cost percentage is not a constant** — it swings 12× with position size, because ₹20 brokerage per order is flat while everything else is proportional. That's why it's computed per trade rather than assumed.

It also matters more than it looks. On that 1-lot position, a *gross* gain of 1% is a **net loss**: costs are 1.55%. Measuring from entry, the bot would have announced a profit on a losing trade. Measuring from breakeven, it stays quiet until you're genuinely ahead.

Concretely, `basePrice` becomes the cost-inclusive breakeven, and everything measures from it — phase transitions, milestone notifications, and ownership locks. So PHASE_2 ("base capital protection") now means your capital is genuinely safe, and "lock 30% of open profit" locks 30% of money you'd actually keep.

Two things to expect:

- **These figures read lower than Upstox's own P&L screen**, which shows gross. Same position, two numbers; ours is the one you take home.
- **Everything before exit is an estimate**, and labelled as such. Sell-side charges depend on the exit price, which isn't known at entry, so they're quoted at the entry price. Near breakeven that's worth ₹0.31 on a ₹46.7k position, but it drifts with distance: ₹18.84 at a +21% exit, ₹89.70 at +100%.

**When the trade closes, charges are recomputed at the real exit price** and the settled figure is reported — gross, actual charges, the entry-time estimate for comparison, and net:

```
settled: gross +25704.90, charges 207.21 (estimated 157.87 at entry), net +25497.69
```

If the charges call fails, a built-in model takes over and the figure is labelled `modelled` rather than `broker-quoted`. It's calibrated against real quotes and separates derivatives from equity (options genuinely cost several times intraday equity): within ₹0.09 of the broker on the ACC position above. The model also runs **alongside** every broker quote, reporting the delta, so it stays honest over time.

### Risk-based position sizing

`CAPITAL_PER_TRADE` caps exposure; it does **not** cap losses. At ₹50,000 with the OPTIONS hard stop of 40%, a stopped-out trade loses **₹18,813** — verified, not estimated.

Tightening the stop is the wrong fix: 40% of an option premium is ordinary intraday noise, and at 20% you'd be stopped out of most trades that go on to work. Rupee risk is `stop% × invested`, so the lever is size, not stop width. Set `MAX_RISK_PER_TRADE` and the position is sized so hitting the stop costs that much:

```
lots = min( capital / lotCost , maxRisk / (lotCost × hardStop%) )   then capped at the freeze limit
```

| `MAX_RISK_PER_TRADE` | Lots | Deployed | Loss at the stop |
|---|---|---|---|
| unset | 13 | ₹46,729 | ₹18,813 |
| ₹10,000 | 6 | ₹21,567 | ₹8,627 |
| ₹5,000 | 3 | ₹10,784 | ₹4,313 |

The hard stop comes from the **active milestone set**, so switching sets re-sizes accordingly. `MAX_RISK_PER_TRADE` seeds the value at startup; `/risk <amount>` changes it mid-session without a restart, and `/risk` alone reports what's in force.

### Expiry day and cheap premiums

All NIFTY option contracts have lot 65, freeze 1,755 and a ₹0.05 tick — so **27 lots per order**, every strike. Three things change as premiums get cheap:

| Premium | Max deployable (27 lots) | 1 tick | Round-trip cost | Real breakeven |
|---|---|---|---|---|
| ₹55.30 | ₹97,052 | 0.1% | 0.29% | +0.36% |
| ₹5.00 | ₹8,775 | 1.0% | 0.77% | +1.00% |
| ₹1.00 | ₹1,755 | 5.0% | 2.93% | **+5.00%** |
| ₹0.50 | ₹878 | 10.0% | 5.62% | **+10.00%** |

1. **The freeze limit binds before your capital does.** At ₹1 premium, 27 lots is ₹1,755 — you cannot deploy ₹50,000 in one order, and orders aren't sliced.
2. **One tick becomes a large percentage**, so breakeven has to be **rounded up to a tradable price**. At ₹1 the computed breakeven is ₹1.0293, which nobody can sell at; the real one is ₹1.05, making breakeven +5% rather than +2.93%.
3. **Costs stop being a rounding error** — 2.93% of deployed at ₹1, 5.62% at ₹0.50.

`/track` warns rather than refuses in each case: on expiry day a cheap lottery ticket may be exactly what you intend, but you'll be told when the freeze cap is binding, when costs exceed 2% of deployed, and when one tick is coarser than the first ladder rung (which makes the early milestones fire together).

## Milestone sets

An option premium moves much further than an equity price, so one ladder cannot serve both. Send `/ladder` and the bot replies with the available sets as tappable buttons; the active one is marked.

| | EQUITY | OPTIONS |
|---|---|---|
| Rungs (net of costs) | 1, 2, 3, 5, 8, 13, 21, 34, 55% | 1, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233% |
| PHASE_2 (capital safe) | 2% | 8% |
| PHASE_3 + locking starts | 5% | 21% |
| Ownership locks | 5/8/13/21/34/55 → 30/50/65/75/85/90% | 21/34/55/89/144/233 → 30/50/65/75/85/90% |
| Hard stop | 20% | 40% |

Both ladders open at **1% net** — your minimum worthwhile target, after costs. Before that, the only event is the breakeven notification.

The **hard stop moves with the set**: 20% below entry is a disaster stop on an equity and a routine wiggle on an option premium, so left at 20% nearly every option trade would stop out on noise.

PHASE_2 sits higher for options (8% vs 2%) for the same reason — it ratchets the stop to breakeven, and placing that too early flat-stops trades that were about to work. The first ownership lock always coincides exactly with PHASE_3; a gap between them would be a dead zone where you're in PHASE_3 with the stop parked at breakeven.

**These are reasoned starting points, not values derived from data.** Run paper mode on live data and move the rungs to match what you actually see.

Selection rules:

- **One set is active at a time**, and it applies to trades opened after you choose it.
- **You cannot switch while a trade is open.** Open trades keep the set they were opened with, so switching mid-flight would make "active" mean something other than what is managing your position. Exit first, then choose.
- Numbers live in `MilestoneLadder` — changing them is a code change, deliberately, since they encode trading intent worth reviewing in a diff. Only the *choice* is a runtime decision.

## Taking back control

This app **never places a buy order**. It's purely an exit engine: every position it manages was bought elsewhere and handed to it by `/track`. So it has to be possible to hand one back.

`/exit` sells. These don't:

| Mode | Notifications | Stop tracked | Auto exit |
|---|---|---|---|
| `MANAGED` (default) | Yes | Yes | Yes |
| `OBSERVED` | Yes, including stop breaches | Yes | **No** |
| `RELEASED` | No | No | No |

- **`/release <orderId>`** — the position stays open, the engine stops acting on it. The reply quotes the stop it was holding, because that protection disappears with it.
- **`/observe <orderId>`** — usually what you want when taking manual control: milestones and stop breaches are still reported, but nothing is ever sold. The information stays useful; the trigger is yours.
- **`/manage <orderId>`** — hand it back to the engine.
- **`/pause` / `/resume`** — control whether *new* trades are adopted. Open trades stay managed, and the reply says so; use `/release all` if you want the engine off everything.

`/pause` matters most for Phase 3. Once the broker fill feed is wired in, it streams fills for the **whole account** — a trade punched into the Upstox app, a position from another strategy — and the engine would start managing positions it was never meant to touch. Until adoption is explicitly opt-in, `/pause` is what stands in the way.

## Setting up your Telegram bot

1. In Telegram, search for **`@BotFather`**, tap **Start**, send `/newbot`, and follow the prompts (display name, then a unique username ending in `bot`). It replies with your **bot token** — this is `TELEGRAM_BOT_TOKEN`.
2. Message your new bot at least once (e.g. `hi`) — Telegram won't let a bot message you until you've messaged it first.
3. Search for **`@userinfobot`**, tap **Start** — it replies with your numeric `Id:`. That's `TELEGRAM_CHAT_ID`.
4. (Optional) Register the command list so it autocompletes in the chat. Either send `/setcommands` to BotFather, pick your bot, and paste:
   ```
   track - Manage the exit of a position you hold: /track <symbol> [price] [qty]
   status - Open trades: entry, breakeven, current, phase, stop, mode
   exit - Sell now: /exit <orderId> or /exit all
   observe - Keep the alerts, stop automatic exits (position stays open)
   release - Stop watching entirely; position stays open
   manage - Hand a trade back to the engine
   pause - Stop taking on new trades
   resume - Resume taking on new trades
   risk - Rupees a trade may lose at its stop: /risk <amount> or /risk off
   ladder - Choose the active milestone set
   refresh - Re-fetch the instrument master now
   help - Show every command
   ```
   …or skip BotFather entirely and POST the same list to Telegram's [`setMyCommands`](https://core.telegram.org/bots/api#setmycommands) API with your bot token. Ordered by how often each is reached for, not alphabetically. `/start` isn't listed — Telegram sends it automatically when a user first opens the bot, and it shows the same output as `/help`.

## Bot commands

| Command | Effect |
|---|---|
| `/track <symbol>` | Start managing the exit of a position you already hold, at LTP, sized from `CAPITAL_PER_TRADE` (live mode) |
| `/track <symbol> <qty>` | As above with an explicit quantity |
| `/track <symbol> <price> <qty>` | Fully explicit; the only form available in simulated mode |
| `/exit <orderId>` | Force-exit that trade |
| `/exit all` | Force-exit every open trade |
| `/status` | Report all open trades: instrument, entry, current price, phase, stop-loss |
| `/refresh` | Re-fetch the instrument master now, instead of waiting for Wednesday |
| `/pause` / `/resume` | Stop / resume adopting new trades. Open trades stay managed |
| `/release <orderId>` / `all` | Hand a trade back: position stays open, engine stops acting |
| `/observe <orderId>` / `all` | Keep the notifications, drop the automatic exit |
| `/manage <orderId>` / `all` | Return a trade to full management |
| `/ladder` | Show the milestone sets as buttons and pick one |
| `/ladder <name>` | Select a set directly, skipping the buttons |
| `/risk` | Show the current per-trade risk ceiling |
| `/risk <amount>` / `/risk off` | Set or remove it, applying to trades opened afterwards |
| `/help` | Show every command. `/start` shows the same |

Anything else starting with `/` gets an "unknown command" reply pointing at `/help`. That's deliberate: a mistyped command that silently did nothing would leave you believing a position was being watched when it wasn't. Ordinary chat is ignored, so the bot only answers command-shaped input.

## Running tests

```bash
mvn test              # full suite (248 tests)
mvn test -Dtest=PhaseManagerTest   # a single test class
```

## Troubleshooting

- **`Missing required environment variable: TELEGRAM_BOT_TOKEN`** — you're running `java -jar` directly (not `./run.sh`) without the env vars set in that shell, or `run.sh` still has placeholder values.
- **`TRADING_MODE=... is not supported yet`** — only `paper` is wired up right now; leave `TRADING_MODE` unset (it defaults to `paper`) or set it explicitly to `paper`. Note this is about *order execution*, and is separate from `MARKET_DATA` — live prices work fine in paper mode.
- **`Missing required environment variable: UPSTOX_ANALYTICS_TOKEN`** — you set `MARKET_DATA=live` without a token. This is checked at startup rather than on your first `/track`, so it fails immediately instead of mid-session.
- **`MARKET_DATA=... is not recognised`** — expected `simulated` (default) or `live`.
- **`Missing required environment variable: CAPITAL_PER_TRADE`** — live mode needs it to size a bare `/track <symbol>`. Set it in `run.ps1` / `run.sh`.
- **`unknown instrument: X`** — the symbol isn't in the master. Check spelling against the trading symbol Upstox uses; option symbols look like `NIFTY 25000 CE 18 AUG 26` (spaces optional).
- **`X is an index and cannot be bought`** — expected; trade the option or future instead.
- **`one lot of X costs … which exceeds capital per trade`** — raise `CAPITAL_PER_TRADE`, or pass an explicit quantity to override sizing entirely.
- **Live mode connects but no ticks arrive** — the market is likely closed, or the instrument key is wrong. Check the key against Upstox's instrument list; the index is `NSE_INDEX|Nifty 50`, not `NIFTY50`.
- **No jar found / `run.sh` fails immediately** — run `mvn package` first; `run.sh` looks for `target/xit-mc-*.jar`.
- **Bot doesn't reply to `/track`** — confirm you've messaged the bot at least once already (step 2 above) and that `TELEGRAM_CHAT_ID` is your own numeric id, not the bot's.
- **On Windows, double-clicking `run.sh` prompts "Select an app to open this file"** — expected; Windows has no concept of a bash shebang line, so opening `run.sh` this way never actually runs it, no matter what app you pick. Use `run.ps1` in PowerShell instead (see Quick start above), or run `run.sh` from Git Bash if you have Git for Windows installed.

## Project documentation

| Document | Purpose |
|---|---|
| [PRODUCT_REQUIREMENTS.md](PRODUCT_REQUIREMENTS.md) | Scope, boundaries, features, milestones |
| [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | Phased roadmap, what's done vs. next |
| [CODING_STANDARDS.md](CODING_STANDARDS.md) | Conventions, testing rules, versioning policy |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Detailed snapshot of what's implemented, package by package |
