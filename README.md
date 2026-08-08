# xit-mc

A trade exit management system: you tell it you're in a trade, it watches the live price and manages the exit — stop-loss, phase transitions, and profit-ownership locking — via a deterministic rules engine. It does not decide what to buy or when. See [PRODUCT_REQUIREMENTS.md](PRODUCT_REQUIREMENTS.md) for the full scope.

Right now the runnable mode is **paper trading**: you invoke a trade via a Telegram command, a simulated price feed drives the exit engine, and you get milestone notifications and can force-exit — all without a real broker or real money. Live-broker mode is on the roadmap; see [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).

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
xit-mc started in PAPER trading mode. Send /buy <instrument> <price> <qty> to Telegram to begin.
```

Now message your bot on Telegram:

```
/buy NSE_EQ|INE848E01016 1500 10
```

A simulated price feed starts at ₹1500 and random-walks from there. As it crosses profit milestones (0.5%, 1%, 2%, 3%, 5%, 8%, 13%, 21%, 34%, 55%) you'll get a notification for each; if it drops to the stop-loss, the trade auto-closes and you're notified. Send `/status` anytime to see open trades, or `/exit <orderId>` / `/exit all` to close manually.

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

You should see `... started in PAPER trading mode with LIVE Upstox market data`. Live mode also needs `CAPITAL_PER_TRADE` — see the next section, which covers how `/buy` is used from here on.

Ticks only arrive while the market is open, so outside market hours the engine sits idle rather than reporting an error.

## Symbols, prices and lot sizing (live mode)

Typing `NSE_FO|45148` while scalping is not realistic, so in live mode `/buy` takes a **trading symbol** and fills in the rest:

```
/buy nifty25000ce18aug26        price = LTP, quantity = capital ÷ lot cost
/buy ACC 25                     quantity 25, price = LTP
/buy ACC 1850.5 25              both explicit
/buy NSE_EQ|INE012A01025 …      raw instrument key still works
```

Matching ignores case and spaces, so `NIFTY 25000 CE 18 AUG 26` and `nifty25000ce18aug26` are the same instrument, and `NIFTY50` finds the index. Symbols are looked up in a local copy of Upstox's instrument master (~2 MB, ~43k instruments, cached in `~/.xit-mc/instruments`) — a map lookup, not an API call, so it costs nothing in the hot path. With exactly one trailing number it is read as a **quantity**, never a price.

**Quantity is sized in whole lots**, which matters for F&O where quantity must be a multiple of the contract lot:

```
lots = floor(CAPITAL_PER_TRADE / (ltp × lotSize))     capped at floor(freezeQty / lotSize)
```

Equities have `lotSize = 1`, so the same formula gives plain capital ÷ price. Worked examples at `CAPITAL_PER_TRADE=50000`:

| `/buy` | LTP | Lot | Result |
|---|---|---|---|
| `ACC` | 1,362.80 | 1 | 36 → ₹49,061 |
| `nifty25000ce18aug26` | 55.30 | 65 | 13 lots = 845 → ₹46,729 |
| `nifty25000pe18aug26` | 431.45 | 65 | 1 lot = 65 → ₹28,044 |

Two deliberate behaviours:

- **Capped, not sliced.** Exchanges reject single orders above a freeze quantity (1,755 for NIFTY, i.e. 27 lots). When your capital would buy more — routine on expiry days — the order is capped at that limit rather than split into several. Slicing is a planned enhancement; multiple fills at different prices don't fit the engine's single-entry-price model yet.
- **Refused, not zero-sized.** If capital won't cover even one lot, `/buy` reports why instead of opening a zero-quantity trade.

Indices are streamable but **cannot be bought** — `/buy NIFTY50` is refused, since a position exists only in the corresponding option or future.

A handful of trading symbols are ambiguous (`CHOLAFIN`, `MOTHERSON`, `ELECTCAST`, `IMC1`, `SILVER`). Rather than guess, `/buy` lists the candidates and asks for the full instrument key.

In simulated mode there is no instrument master and no price source, so `/buy` still requires `<instrumentKey> <price> <qty>` in full.

### Instrument master refresh

The master refreshes **weekly, on Wednesdays** — the cache is stamped with the most recent Wednesday, so the first run on or after one downloads and every run until the next reads from disk. That keeps the ~2 MB download and 37 MB parse off the VM on the other six days.

The trade-off: between refreshes, contracts listed since Wednesday won't resolve, and expired ones linger. When you need one immediately:

```
/refresh
```

That forces a download regardless of schedule and takes effect on the next command. If it fails, the previous master stays loaded — stale beats none mid-session.

## Setting up your Telegram bot

1. In Telegram, search for **`@BotFather`**, tap **Start**, send `/newbot`, and follow the prompts (display name, then a unique username ending in `bot`). It replies with your **bot token** — this is `TELEGRAM_BOT_TOKEN`.
2. Message your new bot at least once (e.g. `hi`) — Telegram won't let a bot message you until you've messaged it first.
3. Search for **`@userinfobot`**, tap **Start** — it replies with your numeric `Id:`. That's `TELEGRAM_CHAT_ID`.
4. (Optional) Back in BotFather, send `/setcommands`, pick your bot, and paste:
   ```
   buy - Invoke a trade: /buy <instrument> <price> <qty>
   exit - Force-exit a trade: /exit <orderId> or /exit all
   status - List active trades
   refresh - Re-fetch the instrument master now
   ```
   This makes the commands show up as autocomplete suggestions in the chat.

## Bot commands

| Command | Effect |
|---|---|
| `/buy <symbol>` | Invoke a new (paper) trade at LTP, sized from `CAPITAL_PER_TRADE` (live mode) |
| `/buy <symbol> <qty>` | As above with an explicit quantity |
| `/buy <symbol> <price> <qty>` | Fully explicit; the only form available in simulated mode |
| `/exit <orderId>` | Force-exit that trade |
| `/exit all` | Force-exit every open trade |
| `/status` | Report all open trades: instrument, entry, current price, phase, stop-loss |
| `/refresh` | Re-fetch the instrument master now, instead of waiting for Wednesday |

## Running tests

```bash
mvn test              # full suite (172 tests)
mvn test -Dtest=PhaseManagerTest   # a single test class
```

## Troubleshooting

- **`Missing required environment variable: TELEGRAM_BOT_TOKEN`** — you're running `java -jar` directly (not `./run.sh`) without the env vars set in that shell, or `run.sh` still has placeholder values.
- **`TRADING_MODE=... is not supported yet`** — only `paper` is wired up right now; leave `TRADING_MODE` unset (it defaults to `paper`) or set it explicitly to `paper`. Note this is about *order execution*, and is separate from `MARKET_DATA` — live prices work fine in paper mode.
- **`Missing required environment variable: UPSTOX_ANALYTICS_TOKEN`** — you set `MARKET_DATA=live` without a token. This is checked at startup rather than on your first `/buy`, so it fails immediately instead of mid-session.
- **`MARKET_DATA=... is not recognised`** — expected `simulated` (default) or `live`.
- **`Missing required environment variable: CAPITAL_PER_TRADE`** — live mode needs it to size a bare `/buy <symbol>`. Set it in `run.ps1` / `run.sh`.
- **`unknown instrument: X`** — the symbol isn't in the master. Check spelling against the trading symbol Upstox uses; option symbols look like `NIFTY 25000 CE 18 AUG 26` (spaces optional).
- **`X is an index and cannot be bought`** — expected; trade the option or future instead.
- **`one lot of X costs … which exceeds capital per trade`** — raise `CAPITAL_PER_TRADE`, or pass an explicit quantity to override sizing entirely.
- **Live mode connects but no ticks arrive** — the market is likely closed, or the instrument key is wrong. Check the key against Upstox's instrument list; the index is `NSE_INDEX|Nifty 50`, not `NIFTY50`.
- **No jar found / `run.sh` fails immediately** — run `mvn package` first; `run.sh` looks for `target/xit-mc-*.jar`.
- **Bot doesn't reply to `/buy`** — confirm you've messaged the bot at least once already (step 2 above) and that `TELEGRAM_CHAT_ID` is your own numeric id, not the bot's.
- **On Windows, double-clicking `run.sh` prompts "Select an app to open this file"** — expected; Windows has no concept of a bash shebang line, so opening `run.sh` this way never actually runs it, no matter what app you pick. Use `run.ps1` in PowerShell instead (see Quick start above), or run `run.sh` from Git Bash if you have Git for Windows installed.

## Project documentation

| Document | Purpose |
|---|---|
| [PRODUCT_REQUIREMENTS.md](PRODUCT_REQUIREMENTS.md) | Scope, boundaries, features, milestones |
| [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | Phased roadmap, what's done vs. next |
| [CODING_STANDARDS.md](CODING_STANDARDS.md) | Conventions, testing rules, versioning policy |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Detailed snapshot of what's implemented, package by package |
