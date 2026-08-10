# Repo strategy: kb-xit vs. kb-test

> Added August 2026, after several weeks of parallel/unclear work across both repos.

## Decision

**`kb-xit` is the product.** This repo is the exit-management application described in
`PRODUCT_REQUIREMENTS.md` — it is the thing that goes to production for real traders. All
active development happens here.

**`kb-test` is parked**, not deleted. It holds two different things that got mixed together:

1. One genuinely useful, tested module: `market-data-engine` (Upstox historical-candle
   fetching, timeframe aggregation). This is a real candidate to port into `kb-xit` when
   Phase 4 needs a `HistoricalDataFeed` for backtesting against real historical data
   (IMPLEMENTATION_PLAN.md, Phase 4). Not urgent — nothing in the path to production depends
   on it.
2. Nine empty module skeletons (`indicators-engine`, `strategy-composition`, `entry-engine`,
   `scanner-engine`, `trade-lifecycle`, `execution-infrastructure`, `orchestrator-core`,
   `trading-domain`, `app-runner`) plus an extensive, strict architecture spec (`AGENTS.md`,
   `docs/`). These describe **signal generation and full-pipeline automation** — a different
   product from exit management, matching what was described as "later, independent SaaS
   products" once the exit engine is live. There is no code to lose by leaving them exactly
   as they are until that work actually starts.

## Why the split happened and why it isn't a problem to fix later

`kb-test`'s `AGENTS.md` mandates a full event-driven, registry-based, zero-conditional
architecture across ten modules before any of them does anything. That's a reasonable target
for a mature multi-engine platform; it is not achievable as a first MVP, and building toward
it is very likely why nine of those ten modules never got past a `pom.xml`. `kb-xit` grew
in parallel with a much smaller, single-module scope (`CODING_STANDARDS.md`, not `AGENTS.md`)
and reached a working, tested, nearly-production-ready system as a result.

The two repos were never actually building the same thing at different speeds — one is an
exit engine, the other is a future signal-generation platform that hasn't started. Once that's
named, there's no code to reconcile between them beyond the one module above.

## What this means going forward

- Bug fixes, features, and the Phase 3.5/4 work in `IMPLEMENTATION_PLAN.md` happen in `kb-xit`.
- `kb-test` is not touched until the exit engine (this repo) is in production and the team is
  ready to start the signal-generation products. At that point, `kb-test`'s `AGENTS.md` and
  docs are worth revisiting with the same lesson applied: scope the first version to one thin
  slice before enforcing the full architecture.
- When `market-data-engine` is actually needed (Phase 4's historical feed), port only that
  module's code into a new `com.kbquants.historical` (or similar) package in `kb-xit`, adapted
  to implement this repo's `MarketDataFeed` interface — don't pull in `kb-test`'s multi-module
  Maven structure or its `AGENTS.md` constraints along with it.
