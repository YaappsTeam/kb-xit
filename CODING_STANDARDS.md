# xit-mc Coding Standards

> Version 2.0 — August 2026

Standards, conventions, and testing rules for the xit-mc codebase. Follow these when adding or modifying code to keep the project consistent and maintainable.

## 1. Language and build

| Rule | Standard |
|---|---|
| Java version | 17 (source and target) |
| Build system | Maven (`mvn clean test` must always pass) |
| Dependencies | Declared in `pom.xml` with explicit versions — no version ranges, no SNAPSHOT dependencies for third-party libs |
| New dependencies | Justify before adding — prefer JDK built-ins when practical (e.g., `java.net.http.HttpClient` over adding OkHttp for a single HTTP call) |

## 2. Package structure

```
com.kbquants
├── config         Configuration loading and schema
├── domain         Core value/state objects (immutable where possible, includes MilestoneLadder)
├── engine         Exit rules pipeline (deterministic, no I/O)
├── instrument     NIFTY-options instrument master, catalog, strike windowing
├── live           Broker-specific implementations (Upstox) and orchestration (TradeMonitor)
├── marketdata     Historical-candle retrieval (ported from kb-test; not yet wired into Main — see below)
├── notification   Alerting, Telegram bot (inbound commands + outbound notifications)
├── session        Broker-agnostic interfaces (MarketDataFeed, OrderFillFeed, PriceListener, etc.)
└── simulation     Synthetic data, batch execution, reporting
```

**Rules:**

- One responsibility per package. A class that bridges two packages (e.g., wiring feeds to the engine) belongs in the package that owns the orchestration (`live` for live flows, `simulation.runner` for simulation flows).
- No circular dependencies between packages. The dependency direction is: `live` / `simulation` -> `session` -> `engine` -> `domain`. `notification` is a leaf — nothing depends on it except the orchestrators.
- `engine` and `domain` must never import from `live`, `notification`, or `simulation`. They are the pure core.
- **Broker-agnostic interfaces live in `session`**, not `live`. The `session` package defines `MarketDataFeed`, `OrderFillFeed`, `PriceListener`, `TradeFillListener`, and `TradeFillEvent`. The `live` package holds only broker-specific implementations (e.g., `UpstoxMarketDataFeed`, `UpstoxOrderFillFeed`).
- **`marketdata` is self-contained and currently unwired.** It defines its own `HistoricalCandleFeed` interface (pull-based: fetch a candle range) rather than implementing `session.MarketDataFeed` (push-based: live ticks) — the two solve different problems and forcing one onto the other would be a worse fit than two small interfaces. Nothing in `live`, `engine`, or `session` may depend on `marketdata` until a story actually wires historical data into the exit engine (tracked as story #24); until then it is ported code waiting on a consumer, not dead code to delete.

### A note on the modules this repo doesn't have yet

`docs/future-products/` (ported from kb-test) describes a larger signal-generation ecosystem — indicators, strategy composition, entry engine, scanner, orchestrator — that this repo does not build today; this repo's scope is the exit engine plus the market-data/instrument groundwork it needs. Two principles from that plan are worth keeping in mind if and when those modules get built here, rather than as a separate product:

- **Registry pattern over type-switching.** New pluggable strategies/indicators should register into a lookup (as `MilestoneLadder`'s named sets already do for exit ladders — see §3), not get selected via `switch`/`if-else` chains on a type field.
- **Event-driven, not directly coupled, cross-module communication.** A future entry engine or scanner should publish something the exit engine subscribes to, not call into `TradeMonitor` directly — the same reasoning that already keeps `engine` and `domain` free of `live`/`notification`/`simulation` imports (see the package rules above).

Everything else in kb-test's retired `AGENTS.md` (the 90%-coverage mandate, the strict `TradeContext`-only exit-engine sandboxing, the SIGNALLED→...→COMPLETED state machine) was written for a bigger multi-engine system this repo isn't building yet, so it stays there rather than being imposed on this codebase's actual size.

## 3. Class design

### Immutability by default

- Value objects and events (`TradeFillEvent`, `Candle5m`, `MarketSnapshot`, `ScenarioConfig`, `TradeMetrics`) are immutable. All fields `final`, set in the constructor, exposed via getters only.
- `TradeContext` is the explicit exception — it is the single mutable state object for a trade, mutated only by the engine pipeline. Its mutability is intentional and contained.

### Constructor validation

- Validate required arguments at construction time. Use `Objects.requireNonNull()` with a descriptive message for non-null constraints.
- Fail fast with `IllegalStateException` or `IllegalArgumentException` for invalid configurations (e.g., missing access token, empty instrument list).

### Interfaces over abstract classes

- Define extension points as interfaces (`MarketDataFeed`, `OrderFillFeed`, `PriceListener`, `Notifier`, `OwnershipStrategy`, `PricePathGenerator`, `CombinationExecutor`).
- Keep interfaces small — one or two methods maximum. The `Notifier` interface has a single `send(String)` method by design.

### Broker abstraction

- All broker interactions must go through interfaces defined in the `session` package. The orchestrator (`TradeMonitor`) must never import a broker-specific class directly.
- To add a new broker: implement `OrderFillFeed`, `MarketDataFeed`, and (when applicable) `ExitOrderPlacer`. Wire them in `Main`. Zero changes to the orchestrator or engine.
- Broker-specific code (SDK imports, credential handling, WebSocket setup) belongs in the `live` package, never in `session`, `engine`, or `domain`.

### Single source of truth for thresholds

- All profit-from-entry thresholds (notifications, phase transitions, ownership locks) are defined in one `MilestoneLadder` object in the `domain` package.
- `PhaseManager`, `ProfitMilestoneTracker`, and `MilestoneOwnershipStrategy` all read from this ladder. None of them hardcode their own thresholds independently.
- Changing the ladder in one place must change the behavior of all three systems.

### Lombok usage

- Use Lombok for boilerplate reduction: `@Slf4j` for loggers, `@Getter`/`@Setter` for domain objects, `@RequiredArgsConstructor` where appropriate.
- Do not use `@Data` on mutable domain objects — it generates `equals`/`hashCode` on mutable fields, which is unsafe if the object is used as a map key.
- Do not use `@Builder` unless the class has more than 4 constructor parameters.

## 4. Naming conventions

| Element | Convention | Example |
|---|---|---|
| Classes | PascalCase, noun or noun phrase | `ProfitMilestoneTracker`, `StopLossEngine` |
| Interfaces | PascalCase, no `I` prefix | `Notifier`, `MarketDataFeed` |
| Methods | camelCase, verb or verb phrase | `checkAndAdvance()`, `onPriceUpdate()` |
| Constants | UPPER_SNAKE_CASE | `HARD_SAFETY_PERCENT`, `DEFAULT_THRESHOLDS_PERCENT` |
| Test classes | `{ClassUnderTest}Test` | `PhaseManagerTest`, `TelegramNotifierTest` |
| Test methods | `should{ExpectedBehavior}` | `shouldFireExactlyAtFirstThreshold()` |
| Packages | all lowercase, no underscores | `com.kbquants.simulation.runner` |
| Event classes | `{Noun}Event` | `TradeFillEvent` |
| Listener interfaces | `{Noun}Listener` | `PriceListener`, `TradeFillListener` |
| Credentials holders | `{Service}Credentials` | `UpstoxCredentials`, `TelegramCredentials` |

## 5. Error handling

### Fail fast at boundaries

- Validate inputs at system boundaries: constructors, public API entry points, environment variable reading.
- Use `Objects.requireNonNull()` for null checks, `IllegalArgumentException` for invalid values.

### Never throw from notification/monitoring code

- `TelegramNotifier.send()` catches all exceptions and logs them. A notification failure must never interrupt live price monitoring.
- This pattern applies to all `Notifier` implementations — the contract is best-effort delivery.

### Let engine code throw

- The exit engine (`ExitEngine`, `PhaseManager`, `StopLossEngine`, ownership strategies) is pure computation over `TradeContext`. If it receives invalid input (e.g., null context), letting it throw is correct — it means the caller is broken.

### Logging, not swallowing

- Never silently catch and ignore exceptions. If you catch, log at an appropriate level:
  - `log.error()` — something is broken and needs attention
  - `log.warn()` — something unexpected but recoverable (e.g., failed notification)
  - `log.info()` — operational events (fill detected, milestone crossed, feed connected)
  - `log.debug()` — diagnostic detail (duplicate fill ignored, price update below threshold)

## 6. Concurrency

- Use `ConcurrentHashMap` for shared mutable state accessed from WebSocket callback threads (as in `LiveProfitAlertRunner.trackersByOrderId`).
- Use `putIfAbsent()` for deduplication guards — never `containsKey()` followed by `put()` (race condition).
- Keep critical sections small. Do not hold locks while making network calls.
- The engine pipeline (`ExitEngine.onPriceUpdate`) is single-threaded per trade — one `PriceListener` callback per instrument feed. Do not introduce shared mutable state between trades unless explicitly required.

## 7. Credential management

- **Never** commit secrets, tokens, passwords, or API keys to the repository.
- All credentials are read from environment variables at runtime via `*.fromEnv()` factory methods.
- `fromEnv()` methods accept a `Function<String, String>` overload for testability (inject a fake environment).
- Document every environment variable in `PRODUCT_REQUIREMENTS.md` (section 6) and in the class's Javadoc.
- Access tokens are ephemeral (Upstox tokens expire daily). The system must handle token absence gracefully (fail fast with a clear message, not a NullPointerException deep in a WebSocket callback).

## 8. Comments

### When to write a comment

- Explain **why**, not **what**. If the code is clear, no comment is needed.
- Document non-obvious constraints: "case-insensitive match because exact casing from Upstox could not be verified against a live payload."
- Document intentional design decisions: "single-method interface so this can later be replaced with order placement."
- Mark known limitations: "not connectivity-tested — see DEVELOPMENT.md."

### When NOT to write a comment

- Do not describe what a method does if the method name already says it (`// Returns the entry price` above `getEntryPrice()`).
- Do not reference tasks, tickets, or conversations ("added for the profit-alert feature").
- Do not write multi-paragraph Javadoc on internal/private classes. A one-liner is enough.

## 9. Testing rules

### Test philosophy

- **Every class with logic gets a test class.** "Logic" means conditionals, calculations, state transitions, or filtering. Pure data holders (getters/setters only) do not need dedicated tests.
- **Tests verify behavior, not implementation.** Test what a class does (its observable outputs and side effects), not how it does it internally.
- **No network calls in tests.** All broker/API interactions are tested via fakes, stubs, or hand-built SDK objects. Real network calls require a live environment and are out of scope for `mvn test`.

### Test structure

```java
@Test
void shouldDescribeExpectedBehavior() {

    // Arrange — set up inputs and dependencies
    ProfitMilestoneTracker tracker = new ProfitMilestoneTracker(100.0);

    // Act — call the method under test
    OptionalDouble result = tracker.checkAndAdvance(100.5);

    // Assert — verify the outcome
    assertTrue(result.isPresent());
    assertEquals(0.5, result.getAsDouble());
}
```

**Rules:**

1. **One concept per test.** Each `@Test` method tests one behavior or scenario. Use descriptive `should...` names.
2. **Arrange-Act-Assert structure.** Separate setup, execution, and verification with blank lines. No logic in the assert section.
3. **No test interdependence.** Tests must pass in any order. No shared mutable state between test methods.
4. **Boundary testing.** For threshold-based logic (phase transitions, milestones, stop-loss), test: below threshold, exactly at threshold, above threshold, and just-below-threshold.
5. **Ratchet testing.** For any ratcheting behavior (stop-loss, milestone tracking), verify: value advances, value does not go backward, repeated same-value input does not re-trigger.

### What to fake

| Component | Fake approach | Example |
|---|---|---|
| `MarketDataFeed` | Implement interface; capture the `PriceListener` | `FakeFeed` in `LiveProfitAlertRunnerTest` |
| `OrderFillFeed` | Implement interface; expose a method to inject fills | `FakeOrderFillFeed` in `TradeMonitorTest` |
| `Notifier` | Implement interface; record `send()` calls in a list | `RecordingNotifier` in `LiveProfitAlertRunnerTest` |
| `MilestoneLadder` | Construct with custom thresholds | `new MilestoneLadder(customMilestones)` |
| Environment variables | Pass `Function<String, String>` to `fromEnv()` | `Map.of(...)::get` in `UpstoxCredentialsTest` |
| SDK objects (`OrderUpdate`) | Construct directly and set fields | `orderUpdate()` helper in `UpstoxOrderFillFeedTest` |
| Network calls (HTTP, WebSocket) | Do not test at the unit level | Covered by integration/manual testing |

### What NOT to do in tests

- Do not use mocking frameworks (Mockito, etc.) when a simple fake/stub implementation suffices. The codebase currently uses no mocking library — keep it that way unless the faking cost becomes unreasonable.
- Do not test private methods directly. Test them through the public API.
- Do not write tests that depend on timing (`Thread.sleep`, `await().atMost()`). The engine is synchronous and deterministic by design.
- Do not test Lombok-generated code (getters, setters, constructors).

### Running tests

```bash
mvn test           # Run all tests
mvn test -pl .     # Run tests in root module only
mvn test -Dtest=PhaseManagerTest   # Run a single test class
```

All tests must pass before committing. A failing test is a blocking issue.

## 10. Git conventions

### Commit messages

- Start with a verb in imperative mood: "Add", "Fix", "Remove", "Update", "Wire", "Refactor"
- One sentence, under 72 characters for the subject line
- Use the body (blank line after subject) for context if the change is non-obvious
- Examples:
  - `Add MVP: Telegram profit-milestone alerts triggered by order fills`
  - `Fix duplicate feed creation on repeated fill events`
  - `Wire ParallelCombinationExecutor into SimulationRunner`

### Branch naming

- Feature branches: `feature/{short-description}`
- Fix branches: `fix/{short-description}`
- Documentation: `docs/{short-description}`

### What to commit

- Source code, tests, documentation (`.md`), build files (`pom.xml`)
- Never: IDE settings (`.idea/`, `.vscode/`), compiled output (`target/`), credentials, tokens, `.env` files

## 11. SDK verification protocol

When integrating with external SDKs (Upstox, Telegram, future brokers):

1. **Never guess method signatures.** If the API docs are incomplete or inaccessible, download the SDK jar and decompile with `javap -p <classname>` to get exact signatures.
2. **Inspect the sources jar** for implementation details (field names, enum values, request paths).
3. **Build hand-constructed SDK objects** in tests (e.g., `new OrderUpdate()` with `setStatus("complete")`) rather than relying on mocking frameworks that might hide signature mismatches.
4. **Document the verification** in test-class Javadoc (e.g., "method signatures verified via javap against upstox-java-sdk-1.27.jar").
5. **Flag unverified assumptions** with comments (e.g., "matched case-insensitively — exact casing from live payload could not be verified").

## 12. Versioning (Semantic Versioning)

xit-mc follows [Semantic Versioning 2.0.0](https://semver.org/): `MAJOR.MINOR.PATCH`, tracked in `pom.xml`'s `<version>` tag (currently `0.4.0-SNAPSHOT`).

**Pre-1.0 (`0.x.y`).** Per the SemVer spec (§4), while the major version is `0` the public API is considered unstable — any interface can still change between minor versions without it counting as a breaking change in the strict sense. We move to `1.0.0` once the live-broker path (Phase 3 in IMPLEMENTATION_PLAN.md) is wired up and connectivity-verified against real Upstox/Telegram servers. Until then, MINOR is the practical ceiling for day-to-day bumps.

**Bump rules** — decide from the single most significant change in the commit/PR:

| Change type | Bump | Example |
|---|---|---|
| Breaking change to a public interface, package move affecting callers, or incompatible behavior/config change | MAJOR (post-1.0) / MINOR (pre-1.0, per SemVer §4) | Changing `MarketDataFeed.start()`'s signature, moving a class consumers depend on |
| New backward-compatible feature or capability | MINOR | Adding `TelegramCommandHandler`, a new `OwnershipStrategy`, paper trading mode |
| Backward-compatible bug fix, internal refactor, or dependency bump with no behavior change for consumers | PATCH | Fixing a threshold boundary bug, tightening a log message |
| Documentation-only change (`.md` files, comments) | No bump | Updating `DEVELOPMENT.md`, `PRODUCT_REQUIREMENTS.md` |

**When to bump:** as part of the same commit/PR that introduces the change — never a separate follow-up commit. Only one component moves per bump: bumping MINOR resets PATCH to `0`; bumping MAJOR resets MINOR and PATCH to `0`.

**`-SNAPSHOT` suffix:** kept for the entire lifetime of active development on a version. Only drop it when actually cutting a release build/tag — day-to-day commits on this branch always carry `-SNAPSHOT`.

**Where:** `pom.xml` → `<version>X.Y.Z-SNAPSHOT</version>` is the single source of truth. If a version-history comment above it (like the one currently there) would go stale or misleading after the bump, update or remove it in the same commit.

## 13. Checklist for new code

Before submitting any change:

- [ ] `mvn test` passes with all tests green
- [ ] New logic has corresponding test(s) following the rules in section 9
- [ ] No secrets, tokens, or credentials in the code or commit
- [ ] No new dependencies added without justification
- [ ] Interfaces used for extension points (not abstract classes)
- [ ] Constructor validates required arguments
- [ ] Network-facing code handles failures gracefully (log, don't throw)
- [ ] Engine/domain code remains free of I/O imports
- [ ] Package dependency direction is respected (no circular deps)
- [ ] Broker-specific code is behind an interface in `session`; orchestrator never imports broker classes
- [ ] Thresholds come from `MilestoneLadder`, not hardcoded independently
- [ ] `pom.xml` version bumped per section 12, if this change touches `src/main`
- [ ] Commit message follows conventions (section 10)
