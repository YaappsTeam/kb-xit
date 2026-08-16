# xit-mc

A trade exit management system: you tell it you're in a trade, it watches the live price and manages the exit — stop-loss, phase transitions, and profit-ownership locking — via a deterministic rules engine. It does not decide what to buy or when. See [PRODUCT_REQUIREMENTS.md](PRODUCT_REQUIREMENTS.md) for the full scope.

**It never places a buy order.** Every position it manages was bought elsewhere and handed to it — so it can also be handed back, without closing anything.

Right now the runnable mode is **paper trading**, on either a simulated price feed or **real Upstox market data**. You declare a trade via Telegram, the engine watches the price and manages the exit, and every figure it reports is **net of brokerage and taxes**. Real sell orders are written but off by default — see [Placing real sell orders](#placing-real-sell-orders) for what turning them on requires.

## Prerequisites

- Java 17
- Maven
- A Telegram bot (one bot token, shared by every account) — see [Setting up your Telegram bot](#setting-up-your-telegram-bot) below if you don't have one yet
- At least one registered trader account (chat id + Upstox settings) — see [Registering accounts](#registering-accounts) below

## Quick start (paper trading)

```bash
git clone https://github.com/YaappsTeam/kb-xit.git
cd kb-xit
```

**Register at least one account:**

```bash
cp accounts.properties.example accounts.properties
```

Edit `accounts.properties` and fill in at least one trader's real chat id (see [Setting up your Telegram bot](#setting-up-your-telegram-bot) for how to find it). The other fields (Upstox credentials) can stay as placeholders for paper trading with simulated prices — they're only read when `MARKET_DATA=live`.

Pick the path for your shell:

**macOS / Linux / Git Bash — `run.sh`:**

```bash
cp run.sh.example run.sh
```

Edit `run.sh` and fill in your bot token:

```bash
export TELEGRAM_BOT_TOKEN="your-telegram-bot-token-here"
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

Edit `run.ps1` and fill in your bot token:

```powershell
$env:TELEGRAM_BOT_TOKEN = "your-telegram-bot-token-here"
```

Build and run:

```powershell
mvn package
./run.ps1
```

---

`run.sh` / `run.ps1` / `accounts.properties` are all gitignored — none of them risk being committed. Never edit the `.example` files with real values; those files *are* tracked.

You should see:

```
xit-mc started in PAPER trading mode with simulated market data, managing 1 account(s): [alice]. Send /track <symbol> to Telegram to begin.
```

Now message your bot on Telegram from the chat you registered:

```
/track NSE_EQ|INE848E01016 1500 10
```

A simulated price feed starts at ₹1500 and random-walks from there. You will first be told when the trade clears breakeven (brokerage and taxes covered), then as it crosses each net profit milestone (1%, 2%, 3%, 5%, 8%, 13%, 21%, 34%, 55%); if it drops to the stop-loss, the trade auto-closes and you're notified. Send `/status` anytime to see open trades, or `/exit <orderId>` / `/exit all` to close manually.

Stop the process with `Ctrl+C` — it shuts down the Telegram poller cleanly.

## Registering accounts

Every trader this deployment manages gets one entry in `accounts.properties` (copied from `accounts.properties.example`, gitignored). One process, one bot token, up to ten independent accounts — each with its own Telegram chat, its own Upstox credentials, its own capital and risk settings, its own open trades, and its own persistence file. See PRODUCT_REQUIREMENTS.md section F8 for the full isolation model.

```properties
account.alice.telegramChatId=111222333
account.alice.upstoxAnalyticsToken=alice-analytics-token-here
account.alice.upstoxApiKey=alice-api-key
account.alice.upstoxApiSecret=alice-api-secret
account.alice.upstoxRedirectUri=https://example.com/upstox/callback
account.alice.capitalPerTrade=50000
account.alice.maxRiskPerTrade=5000
account.alice.defaultMilestoneSetName=EQUITY
```

A message from a chat id that isn't registered gets a polite refusal and touches nothing — the bot never guesses which account a stranger's message belongs to. Adding an eleventh trader is a config change and a restart, not a code change; there's no in-session "register a new account" command by design (see REPO_STRATEGY.md / IMPLEMENTATION_PLAN.md Phase 3.5 for why).

`ACCOUNTS_FILE` overrides the path if you don't want `accounts.properties` in the working directory.

## Live market data (paper trades, real prices)

Set `MARKET_DATA=live` to drive the exit engine from real Upstox ticks instead of the simulated random walk, for every registered account. Trades stay on paper — nothing places a broker order.

This needs an Upstox **Analytics Token** per account, which is not the same as the standard access token:

| | Analytics Token | Standard access token |
|---|---|---|
| Validity | **1 year** | Until 3:30 AM the next day |
| How to get it | Developer Apps → **Analytics** tab → *Generate Token*. No OAuth redirect, no 2FA | Interactive authorization-code login, every trading day |
| Market data + WebSocket streaming | ✅ (no static IP needed) | ✅ |
| Placing/modifying orders | ❌ read-only | ✅ |

Live prices only ever need the first one — so live-data mode involves **no daily login**, and the token it uses physically cannot place an order. The daily token and a registered static IP are needed only for the things that touch an account: placing orders, and checking positions.

Set each account's `upstoxAnalyticsToken` in `accounts.properties`, then add to your `run.ps1` / `run.sh`:

```powershell
$env:MARKET_DATA = "live"
```

```bash
export MARKET_DATA="live"
```

You should see `... started in PAPER trading mode with LIVE Upstox market data, managing N account(s)`. Each account's `capitalPerTrade` (in `accounts.properties`) sizes that account's bare `/track <symbol>` — see the next section, which covers how `/track` is used from here on.

Ticks only arrive while the market is open, so outside market hours the engine sits idle rather than reporting an error.

**If a price feed drops, that account is told.** Without prices the stop-loss stops being enforced, and nothing else would make that visible — the bot looks healthy while the position is unprotected. The alert names the stop that is no longer being applied; reconnection is automatic and recovery is announced too. Each feed is closed when its trade closes or is released, so a session's trades don't leave a connection open apiece.

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

## End-of-day close

Set `EOD_EXIT_TIME` (e.g. `15:15`) and open trades are closed out at that wall-clock time, before your broker squares them off at whatever price it gets. Unset, nothing happens — closing positions on a clock has real consequences, so it's opted into rather than assumed.

`EOD_TIMEZONE` defaults to **`Asia/Kolkata`**, deliberately not the machine's zone: a VM on UTC would otherwise fire five and a half hours late, well after the broker had already acted.

- **`MANAGED` trades are closed**, moved to `PHASE_4`, and settled with real exit-price charges.
- **`OBSERVED` trades are not.** That mode means you took the trigger back, so you get a warning to close it yourself rather than a silent sale.
- **`RELEASED` trades are left alone entirely.**

The schedule runs on its own clock rather than off price ticks — a tick-driven check wouldn't fire when the feed is dead or the instrument is quiet, which is exactly when an unattended position most needs closing. Starting the app after the cutoff won't retroactively square anything off.

## The daily order token

Market data uses the year-long Analytics Token and needs nothing daily. **Placing orders is different**: it needs the standard Upstox access token, which dies at **3:30 AM** and can only be obtained through an interactive browser login with 2FA. That can't be automated, so it's supplied at runtime:

```
/token <value>     # after logging in to Upstox
/token             # shows status, never the token itself
```

The token is never logged, never persisted, and never echoed back — not even partially. **The message you send it in is deleted automatically**, because a credential pasted into chat otherwise stays in the history on both devices and on Telegram's servers. That's best-effort (Telegram refuses deletions older than 48 hours), so you're also reminded to check.

Expiry follows Upstox's rule rather than a 24-hour timer: the token expires at the **first 3:30 AM after it was supplied**. One taken at 09:00 lasts until the next morning; one taken at 02:00 has ninety minutes left.

⚠ **Order placement also requires a registered static IP** (SEBI's algo-trading rules). A home connection won't do — the address changes, and re-registering is rate-limited to once a week and invalidates your token each time. Until this runs somewhere with a fixed address, order placement will be refused by Upstox with `UDAPI1221` however valid your token is.

## Placing real sell orders

Off unless `PLACE_REAL_ORDERS=true`. Nothing else implies it — not `MARKET_DATA=live`, not a valid token — because the difference is real money.

It also needs a `/token` for the day and a **registered static IP**. Without the IP, Upstox refuses with `UDAPI1221` and the app says so in those terms, since the message it returns doesn't make clear it's a hosting problem rather than a bad token.

Exits are placed as **MARKET** orders. The decision to be out has already been taken by then, and a limit that doesn't fill leaves the position open with the stop already breached. The cost is slippage, which on a thin option can be material.

Three outcomes, and they're treated differently on purpose:

| Outcome | What happens |
|---|---|
| **Accepted** | Trade closes, feed stops, settled P&L reported |
| **Rejected** (4xx) | Position is still open, so the trade stays managed and a stop-loss exit retries next tick |
| **Unknown** (timeout, 5xx) | Nothing further is placed. The trade switches to `OBSERVED` and you're told to check your broker |

That last row is the important one. A timeout is **not** a rejection — the order may be resting at the exchange. Retrying it could sell a position that's already gone and leave you **short**, turning a finished trade into a new unmanaged one in the opposite direction. So the order id is claimed *before* the request is sent, and a second exit for the same trade is refused outright.

## Surviving a restart

The position doesn't disappear when the process does. Open trades are written to `~/.xit-mc/open-trades.json` (override with `TRADE_STATE_FILE`) whenever something material changes — a new trade, the stop ratcheting, a phase advancing, a milestone crossing, a mode change, a close — and restored on the next start.

What's restored matters more than that it is:

- **The ratcheted stop, as-is.** It may have been tightened far above the hard stop over the life of the trade; recomputing it would silently hand that protection back.
- **The milestone index**, so thresholds already reported don't all fire again on the first tick back.
- Phase, cost-inclusive breakeven, monitoring mode, and the milestone set the trade was opened with.

The file is written to a temp file and moved into place, so a crash mid-write can't leave something unparseable. A corrupt or missing file means starting with no trades rather than refusing to start — with nothing watching the positions, failing to start is the worse outcome.

On resume you get a summary and a warning: a position closed by hand while the process was down is resumed here regardless. Send a `/token` and that gets checked automatically — see below.

## Checking against your broker

`/positions` asks Upstox what's actually open and compares it with what's being managed here. It runs automatically the moment you send a `/token` and there are open trades, since that's the first point at which the broker can be asked anything.

Three answers:

| | What it means | What happens |
|---|---|---|
| **Confirmed** | Broker agrees | Nothing; carry on |
| **Gone / reduced** | Broker holds less than this system thinks, or nothing | The trade is stood down to `OBSERVED` |
| **Untracked** | Broker holds something this system isn't watching | Offered for adoption, same buttons as a detected fill |

**A discrepancy stops the engine acting, and nothing more.** It's never dropped. Acting is what makes a stale trade dangerous: an exit sized from a position that has shrunk sells quantity that isn't there, and selling past flat opens a short — the exit engine opening a trade, which is exactly what it must never do. But the broker can be the one that's wrong (a settled delivery holding, a bad minute at the API), and dropping the trade would be unrecoverable while standing down isn't. You get `/manage <orderId>` to hand it back or `/release <orderId>` to drop it.

If the check itself fails, **nothing changes** and it says so. An error read as "the broker holds nothing" would stand down every open trade at once.

Two things worth knowing:

- It reads *today's positions*, not delivery holdings. A position left open overnight in delivery moves out of positions and will be reported as gone — the reason a discrepancy stands a trade down rather than dropping it.
- The broker reports positions, not orders, so two trades on the same strike are one position to it. A shortfall can't be attributed to one of them, so both stand down.

Untracked positions are how a trade bought *before* the app started gets found: the fill feed only sees fills while it's connected.

## Taking back control

This app **never places a buy order**. It's purely an exit engine: every position it manages was bought elsewhere and handed to it — by `/track`, or by accepting one it spotted at your broker. So it has to be possible to hand one back.

Sent **without a target**, `/exit`, `/release`, `/observe` and `/manage` reply with a button per open trade — labelled with symbol, entry, current price and mode — so you never retype a generated orderId while a position is moving. Tap one, or use the explicit form when you already know the id.

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

`/pause` matters most with the broker fill feed on: it stops anything new being offered while you deal with what's already open.

## Positions opened at your broker

`WATCH_BROKER_FILLS=true` connects to Upstox's order stream, so a buy punched into the mobile app turns up here without being typed in again. Off by default; it needs a `/token` before the stream can start, and waits quietly until one arrives.

**Detected positions are offered, never taken.** The stream carries fills for the *whole account* — a leg of a hedge, a position from another strategy, a long-term holding — and managing those uninvited would apply exit rules never meant for them, and, with `PLACE_REAL_ORDERS` on, eventually sell them. So a detected fill arrives as a message with two buttons:

```
New position detected at your broker: ACC x10 at 100.00 (orderId=order-1).
It is NOT being managed. Adopt it only if you want this system running
its exit rules on it.
[ Manage the exit of this ]  [ Leave it alone ]
```

- **Manage the exit of this** — adopted exactly as if you'd sent `/track`: breakeven, ladder, stop, the lot.
- **Leave it alone** — not offered again. A reconnect replays fills; something you've already declined shouldn't come back each time.

Neither answer touches the position. Declining doesn't sell it and doesn't stop it existing — it only means this app won't watch it.

| Command | |
|---|---|
| `/adopt` | List everything waiting, as buttons |
| `/adopt <orderId>` | Accept one directly |

While `/pause` is on you're still told about detected positions, but accepting one is refused and the offer stays waiting — so nothing is taken on behind a pause, and nothing is lost either. Offers live in memory only: a restart clears what was waiting, while trades you actually adopted are restored like any other (see *Surviving a restart*).

## Setting up your Telegram bot

1. In Telegram, search for **`@BotFather`**, tap **Start**, send `/newbot`, and follow the prompts (display name, then a unique username ending in `bot`). It replies with your **bot token** — this is `TELEGRAM_BOT_TOKEN`.
2. Message your new bot at least once (e.g. `hi`) — Telegram won't let a bot message you until you've messaged it first.
3. Search for **`@userinfobot`**, tap **Start** — it replies with your numeric `Id:`. That's the `telegramChatId` for this trader's entry in `accounts.properties`. Repeat steps 2-3 for every trader you're registering; step 1 (the bot itself) is done once and shared.
4. (Optional) Register the command list so it autocompletes in the chat. Either send `/setcommands` to BotFather, pick your bot, and paste:
   ```
   track - Manage the exit of a position you hold: /track <symbol> [price] [qty]
   status - Open trades: entry, breakeven, current, phase, stop, mode
   exit - Sell now: /exit <orderId> or /exit all
   observe - Keep the alerts, stop automatic exits (position stays open)
   release - Stop watching entirely; position stays open
   manage - Hand a trade back to the engine
   adopt - Take on a position detected at your broker
   positions - Check what is managed here against what your broker holds
   pause - Stop taking on new trades
   resume - Resume taking on new trades
   risk - Rupees a trade may lose at its stop: /risk <amount> or /risk off
   ladder - Choose the active milestone set
   token - Supply the daily order token; bare shows its status
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
| `/exit` | Show a button per open trade, plus "sell all" |
| `/exit <orderId>` / `/exit all` | Sell directly, skipping the buttons |
| `/status` | Report all open trades: instrument, entry, current price, phase, stop-loss |
| `/refresh` | Re-fetch the instrument master now, instead of waiting for Wednesday |
| `/pause` / `/resume` | Stop / resume adopting new trades. Open trades stay managed |
| `/release` / `/observe` / `/manage` | Show a button per open trade |
| `/release <orderId>` / `all` | Hand a trade back: position stays open, engine stops acting |
| `/observe <orderId>` / `all` | Keep the notifications, drop the automatic exit |
| `/manage <orderId>` / `all` | Return a trade to full management |
| `/ladder` | Show the milestone sets as buttons and pick one |
| `/ladder <name>` | Select a set directly, skipping the buttons |
| `/risk` | Show the current per-trade risk ceiling |
| `/risk <amount>` / `/risk off` | Set or remove it, applying to trades opened afterwards |
| `/positions` | Check what is managed here against what your broker actually holds |
| `/adopt` | Show positions detected at your broker as buttons, and take one on |
| `/adopt <orderId>` | Accept one directly, skipping the buttons |
| `/token` | Show whether a usable order token is held (never shows the token) |
| `/token <value>` | Supply the daily order-placement token |
| `/help` | Show every command. `/start` shows the same |

Anything else starting with `/` gets an "unknown command" reply pointing at `/help`. That's deliberate: a mistyped command that silently did nothing would leave you believing a position was being watched when it wasn't. Ordinary chat is ignored, so the bot only answers command-shaped input.

## Running tests

```bash
mvn test              # full suite (248 tests)
mvn test -Dtest=PhaseManagerTest   # a single test class
```

## Troubleshooting

- **`Missing required environment variable: TELEGRAM_BOT_TOKEN`** — you're running `java -jar` directly (not `./run.sh`) without the env vars set in that shell, or `run.sh` still has placeholder values.
- **`account registry file not found or unreadable`** — `accounts.properties` doesn't exist yet; `cp accounts.properties.example accounts.properties` and fill in at least one account, or set `ACCOUNTS_FILE` to point at it.
- **`account '<id>' is missing required field: X`** — that account's entry in `accounts.properties` is incomplete; every field in `accounts.properties.example` except `upstoxSandbox`/`maxRiskPerTrade` is required.
- **`TRADING_MODE=... is not supported yet`** — only `paper` is wired up right now; leave `TRADING_MODE` unset (it defaults to `paper`) or set it explicitly to `paper`. Note this is about *order execution*, and is separate from `MARKET_DATA` — live prices work fine in paper mode.
- **`MARKET_DATA=... is not recognised`** — expected `simulated` (default) or `live`.
- **`unknown instrument: X`** — the symbol isn't in the master. Check spelling against the trading symbol Upstox uses; option symbols look like `NIFTY 25000 CE 18 AUG 26` (spaces optional).
- **`X is an index and cannot be bought`** — expected; trade the option or future instead.
- **`one lot of X costs … which exceeds capital per trade`** — raise that account's `capitalPerTrade` in `accounts.properties`, or pass an explicit quantity to override sizing entirely.
- **Live mode connects but no ticks arrive** — the market is likely closed, or the instrument key is wrong. Check the key against Upstox's instrument list; the index is `NSE_INDEX|Nifty 50`, not `NIFTY50`.
- **No jar found / `run.sh` fails immediately** — run `mvn package` first; `run.sh` looks for `target/xit-mc-*.jar`.
- **Bot doesn't reply to `/track`, or replies "This chat is not a registered xit-mc account"** — confirm you've messaged the bot at least once already (step 2 in Setting up your Telegram bot) and that the chat id in `accounts.properties` matches the numeric id from `@userinfobot`, not the bot's own id.
- **On Windows, double-clicking `run.sh` prompts "Select an app to open this file"** — expected; Windows has no concept of a bash shebang line, so opening `run.sh` this way never actually runs it, no matter what app you pick. Use `run.ps1` in PowerShell instead (see Quick start above), or run `run.sh` from Git Bash if you have Git for Windows installed.

## Project documentation

| Document | Purpose |
|---|---|
| [PRODUCT_REQUIREMENTS.md](PRODUCT_REQUIREMENTS.md) | Scope, boundaries, features, milestones |
| [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | Phased roadmap, what's done vs. next |
| [CODING_STANDARDS.md](CODING_STANDARDS.md) | Conventions, testing rules, versioning policy |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Detailed snapshot of what's implemented, package by package |
| [REPO_STRATEGY.md](REPO_STRATEGY.md) | Why this repo is the product, and what the `kb-test` repo is for |
