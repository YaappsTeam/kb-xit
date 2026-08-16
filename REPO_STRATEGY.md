# Repo strategy: kb-xit vs. kb-test

> Added August 2026. Confirmed as a merge (not a park) later that month. **Merged** the same
> month — see "Merge record" below.

## Decision

**One repo going forward: `kb-xit`.** It reached a working, tested exit-management app
(450 tests at merge time, live Upstox market data, real order-placement code) while
`kb-test` stayed at mostly-empty scaffolding. All active development happens here; `kb-test`
has been merged into this repo (story #20) and is pending archival on GitHub (subtask #36).

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

## Merge record

Done via story #20 / `IMPLEMENTATION_PLAN.md` step 4.0. What actually happened, vs. the
preflight predictions below:

- **Interface collision — resolved differently than planned.** The preflight assumed the
  ported code should implement `kb-xit`'s existing `session.MarketDataFeed`. Once the code
  was actually in front of us, that turned out to be a poor fit: `session.MarketDataFeed` is
  push-based (`start(PriceListener)`, live ticks), while the ported interface is pull-based
  (`getHistoricalCandles(...)`, a bounded historical range) — genuinely different shapes for
  different problems. Forcing one onto the other would have been a worse reconciliation than
  keeping them separate, so the ported interface was kept as its own thing and just renamed
  away from the confusing shared name: `com.kbquants.marketdata.feed.MarketDataFeed` ->
  `HistoricalCandleFeed`. See `CODING_STANDARDS.md` §2 for the package boundary this leaves.
- **Class collision — resolved as planned.** `com.kbquants.marketdata.broker.upstox.
  UpstoxMarketDataFeed` -> `UpstoxHistoricalCandleFeed`; `kb-xit`'s existing live-tick
  `com.kbquants.live.UpstoxMarketDataFeed` is untouched.
- **Dependency version drift — resolved as planned.** Lombok bumped `1.18.30` -> `1.18.42`,
  JUnit `5.10.0` -> `5.10.2`, `jackson-databind 2.21.1` added. `slf4j-api`/`slf4j-simple`
  were not added — `kb-xit` already carries `slf4j-api` transitively via `logback-classic`,
  and `logback-classic` already serves as the test-time SLF4J binding, so adding
  `slf4j-simple` would only have introduced a duplicate-binding warning.
- **`AGENTS.md` — resolved as planned.** Not carried across (the filter-repo pass only kept
  `market-data-engine/` and `docs/`, and `AGENTS.md` lived outside both). Registry-pattern
  and event-driven-communication principles, scoped to modules this repo doesn't build yet,
  folded into `CODING_STANDARDS.md`; the rest left behind in the archived `kb-test`.
- **Package name — kept as-is, not renamed.** The "chosen shapes" section below floated
  `com.kbquants.historical` as an example internal-package name. The ported code's actual
  package, `com.kbquants.marketdata`, was kept unchanged instead — renaming ~20 files'
  package declarations for a cosmetic difference wasn't worth doing.
- **Verification: 467/467 tests pass** (450 kb-xit + 17 ported), full history and authorship
  preserved via `git filter-repo` + `git merge --allow-unrelated-histories`.

## What this document is not

Not an implementation log beyond the "merge record" above. See `IMPLEMENTATION_PLAN.md` step
4.0 for the ordered step-by-step and its acceptance criteria.
