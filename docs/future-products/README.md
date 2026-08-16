# Future products — not built in this repo

These 17 documents were ported from `kb-test` (see `REPO_STRATEGY.md`) and
describe a larger signal-generation ecosystem: indicators, strategy
composition, an entry engine, a scanner, and an orchestrator sitting on top
of this repo's exit engine.

**None of that is built here.** `xit-mc`'s scope today is the exit engine
plus the market-data and instrument groundwork it needs (see
`PRODUCT_REQUIREMENTS.md`). These docs are reference material for *when*
that signal-generation work starts as independent products on top of the
exit engine — the plan the project's earlier direction assumed before the
exit engine became the primary focus — not documentation of code that
exists today.

The one piece of that ported plan already in this codebase is
`com.kbquants.marketdata` (historical-candle retrieval); it is described in
`16_MARKET_DATA_ENGINE.md` and is unwired until story #24 picks it up.
