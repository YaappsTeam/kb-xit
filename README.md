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
cp run.sh.example run.sh
```

Edit `run.sh` and fill in your real values:

```bash
export TELEGRAM_BOT_TOKEN="your-telegram-bot-token-here"
export TELEGRAM_CHAT_ID="your-telegram-chat-id-here"
```

`run.sh` is already gitignored — your token never risks being committed. Never edit `run.sh.example` with real values; that file *is* tracked.

Build and run:

```bash
mvn package
./run.sh
```

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

## Setting up your Telegram bot

1. In Telegram, search for **`@BotFather`**, tap **Start**, send `/newbot`, and follow the prompts (display name, then a unique username ending in `bot`). It replies with your **bot token** — this is `TELEGRAM_BOT_TOKEN`.
2. Message your new bot at least once (e.g. `hi`) — Telegram won't let a bot message you until you've messaged it first.
3. Search for **`@userinfobot`**, tap **Start** — it replies with your numeric `Id:`. That's `TELEGRAM_CHAT_ID`.
4. (Optional) Back in BotFather, send `/setcommands`, pick your bot, and paste:
   ```
   buy - Invoke a trade: /buy <instrument> <price> <qty>
   exit - Force-exit a trade: /exit <orderId> or /exit all
   status - List active trades
   ```
   This makes the commands show up as autocomplete suggestions in the chat.

## Bot commands

| Command | Effect |
|---|---|
| `/buy <instrumentKey> <price> <qty>` | Invoke a new (paper) trade |
| `/exit <orderId>` | Force-exit that trade |
| `/exit all` | Force-exit every open trade |
| `/status` | Report all open trades: instrument, entry, current price, phase, stop-loss |

## Running tests

```bash
mvn test              # full suite (116 tests)
mvn test -Dtest=PhaseManagerTest   # a single test class
```

## Troubleshooting

- **`Missing required environment variable: TELEGRAM_BOT_TOKEN`** — you're running `java -jar` directly (not `./run.sh`) without the env vars set in that shell, or `run.sh` still has placeholder values.
- **`TRADING_MODE=... is not supported yet`** — only `paper` is wired up right now; leave `TRADING_MODE` unset (it defaults to `paper`) or set it explicitly to `paper`.
- **No jar found / `run.sh` fails immediately** — run `mvn package` first; `run.sh` looks for `target/xit-mc-*.jar`.
- **Bot doesn't reply to `/buy`** — confirm you've messaged the bot at least once already (step 2 above) and that `TELEGRAM_CHAT_ID` is your own numeric id, not the bot's.

## Project documentation

| Document | Purpose |
|---|---|
| [PRODUCT_REQUIREMENTS.md](PRODUCT_REQUIREMENTS.md) | Scope, boundaries, features, milestones |
| [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | Phased roadmap, what's done vs. next |
| [CODING_STANDARDS.md](CODING_STANDARDS.md) | Conventions, testing rules, versioning policy |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Detailed snapshot of what's implemented, package by package |
