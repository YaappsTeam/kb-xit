# Repo strategy: kb-xit vs. kb-test

> Added August 2026. Confirmed as a merge (not a park) later that month.

## Decision

**One repo going forward: `kb-xit`.** It reached a working, tested exit-management app
(396 tests, live Upstox market data, real order-placement code) while `kb-test` stayed at
mostly-empty scaffolding. All active development happens here; `kb-test` will be merged into
this repo and then archived.

The merge is **scheduled with Phase 4** in `IMPLEMENTATION_PLAN.md` — see step 4.0 there —
so it lands exactly when the ported code (`market-data-engine`) is needed for the historical
backtest feed, not before, and doesn't add build risk to the Phase 3.5 multi-account work
that actually blocks going to production.

## What is being merged

Only one thing from `kb-test` has real code worth bringing over:

- **`market-data-engine/`** — Upstox historical-candle fetching, timeframe aggregation, 17
  passing tests. Maps directly to Phase 4.4's `HistoricalMarketDataFeed`.

Everything else in `kb-test` is empty modules with heavy architectural docs (`AGENTS.md`,
9-module skeleton) that describe **signal generation and full-pipeline automation** — a
different product line planned for *after* the exit engine is in production. That work will
resume here as new packages once the exit engine is live; the empty scaffolding does not
need to be carried across.

## Chosen shapes (for the merge itself)

Not decisions to revisit each time — recorded here so the merge, when it happens, follows
them without new discussion.

1. **Survivor repo: `kb-xit`.** `kb-test` is imported *into* `kb-xit`, not the other way
   around. The repo that has the real code and the linear working history wins.

2. **History preservation: `git filter-repo` on `kb-test` down to just `market-data-engine/`,
   then merge with `--allow-unrelated-histories` into `kb-xit`.** Full native `git log` /
   `git blame` on the ported code, and `kb-xit`'s existing history stays clean — no
   inheritance of `AGENTS.md`, empty modules, or the 10-module parent POM into the merged
   history alongside it.

3. **Module structure: stay single-module** in `kb-xit`. The ported code becomes an internal
   package (e.g. `com.kbquants.historical`), not a Maven sub-module. Multi-module was
   exactly the shape that stalled in `kb-test` (9 empty modules under a heavy parent POM);
   splitting only makes sense once there's a second real consumer, which will not exist at
   the point of this merge.

4. **`kb-test` afterwards: archived on GitHub, not deleted.** Read-only, stays visible for
   the history/reference of `AGENTS.md` and the docs that shaped the original architecture
   thinking. Deletion is irreversible and buys nothing over archiving.

## Merge preflight (known collision points)

Recorded now so the person doing the merge (or the next AI session) doesn't rediscover them
under time pressure:

- **Interface name collision.** Both repos define a `MarketDataFeed` interface — `kb-xit`'s
  at `com.kbquants.session.MarketDataFeed` (the one every broker implementation already
  plugs into), `kb-test`'s at `com.kbquants.marketdata.feed.MarketDataFeed` (a different,
  historical-candle-shaped one). The ported code must implement `kb-xit`'s existing
  interface, not add a second competing one.
- **Class name collision.** Both repos define an `UpstoxMarketDataFeed` — live WebSocket
  ticks in `kb-xit`, historical candles in `kb-test`. Rename the ported one on the way in
  (e.g. `UpstoxHistoricalCandleFeed`).
- **Dependency version drift.** Lombok `1.18.30` (kb-xit) vs `1.18.42` (kb-test); JUnit
  `5.10.0` vs `5.10.2`; `kb-test` also declares `jackson-databind` and a bare
  `slf4j-api`/`slf4j-simple` pair not currently in `kb-xit`. Reconcile deliberately during
  the merge, don't assume-latest.
- **`AGENTS.md` scope.** `kb-test`'s `AGENTS.md` mandates a repo-wide event-driven +
  registry-only architecture that this codebase does not follow (and does not need to,
  for its scope). Do not carry `AGENTS.md` across as-is — either fold anything still
  relevant into `CODING_STANDARDS.md` and drop the rest, or leave it behind in the archived
  `kb-test` repo entirely.

## What this document is not

Not an implementation of the merge. See `IMPLEMENTATION_PLAN.md` step 4.0 for the ordered
step-by-step, and don't act on any of the "preflight" bullets above without checking that
step's acceptance criteria first.
