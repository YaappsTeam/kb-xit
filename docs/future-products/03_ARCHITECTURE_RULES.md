# ARCHITECTURE RULES
## Trading Platform Engine Ecosystem

Version: 1.0  
Scope: Non-Negotiable System Constraints  
Audience: Developers, AI Agents  

---

# 1. GENERAL RULES

- Every module MUST have a single responsibility
- Modules MUST be independently deployable in future
- All interactions MUST be event-driven
- System behavior MUST be deterministic

---

# 2. MODULE DEPENDENCY RULES

Allowed dependency direction:

trading-domain
    ↑
indicators-engine
    ↑
strategy-composition
    ↑
entry-engine
    ↑
scanner-engine
    ↑
orchestrator-core
    ↓
exit-engine
    ↓
execution-infrastructure

---

# 3. FORBIDDEN DEPENDENCIES

- exit-engine MUST NOT depend on entry-engine
- indicators-engine MUST NOT depend on strategy modules
- execution-infrastructure MUST NOT depend on core logic
- broker modules MUST NOT affect engine behavior
- domain module MUST NOT depend on any other module

---

# 4. EVENT COMMUNICATION RULES

- All cross-module communication MUST use events
- Modules MUST NOT directly invoke other modules
- Events MUST be immutable
- Event payloads MUST contain complete required data

---

# 5. DOMAIN LAYER RULES

- Domain layer MUST contain only data models
- Domain models MUST NOT contain business logic
- Domain models MUST NOT depend on external libraries
- Domain models SHOULD be immutable

---

# 6. INDICATOR ARCHITECTURE RULES

- Indicator algorithm MUST be stateless
- Indicator state MUST be separated
- Indicators MUST be reusable across strategies
- Indicators MUST be computed once per instrument
- Streaming indicators MUST operate in constant time

---

# 7. STRATEGY COMPOSITION RULES

- Strategies MUST be declarative
- Strategies MUST be built using condition trees
- Conditions MUST be composable
- Strategy logic MUST NOT contain execution logic

---

# 8. ENTRY ENGINE RULES

- Entry engine MUST only generate signals
- Entry engine MUST NOT manage trade lifecycle
- Entry engine MUST NOT contain exit logic
- Entry engine MUST NOT enforce risk rules

---

# 9. EXIT ENGINE RULES

- Exit engine MUST operate independently of entry logic
- Exit engine MUST enforce monotonic stop loss
- Exit engine MUST operate on TradeContext only
- Exit engine MUST NOT depend on broker implementations
- Exit engine MUST NOT depend on strategy logic
- Exit engine MUST be single-trade scoped

---

# 10. TRADE LIFECYCLE RULES

Valid states:

SIGNALLED
ORDER_PENDING
ORDER_SENT
FILLED
ACTIVE
EXIT_PENDING
EXIT_SENT
EXIT_FILLED
COMPLETED

Rules:

- State transitions MUST be validated
- Invalid transitions MUST be rejected
- States MUST NOT be skipped
- Trade MUST always end in COMPLETED state

---

# 11. EXECUTION INFRASTRUCTURE RULES

- Execution layer MUST be interface-driven
- Broker implementations MUST be pluggable
- Core system MUST NOT branch on broker type
- Execution MUST be replaceable without affecting core logic

---

# 12. REGISTRY PATTERN RULES

- All extensibility MUST use registries
- New components MUST be registered, not hardcoded
- Factory pattern MUST be used for object creation

---

# 13. FORBIDDEN IMPLEMENTATION PATTERNS

- switch-case based type handling
- if-else chains for strategy selection
- static mutable state
- global variables
- hidden singletons
- circular dependencies
- cross-module direct method calls

---

# 14. STATE MANAGEMENT RULES

- State MUST be explicit
- State MUST NOT be globally shared
- State MUST be scoped to context objects
- Trade state MUST reside inside TradeContext

---

# 15. CONFIGURATION RULES

- Configuration MUST be externalized
- Configuration MUST NOT be hardcoded
- Runtime behavior MUST be configurable without code changes

---

# 16. LOGGING RULES

- Logging MUST be structured
- All major state transitions MUST be logged
- All trade-related events MUST include identifiers

---

# 17. TESTABILITY RULES

- All modules MUST be independently testable
- Business logic MUST be isolated from infrastructure
- Deterministic behavior MUST be testable

---

# 18. PERFORMANCE RULES

- Indicator computation MUST avoid duplication
- Streaming operations MUST be constant time
- Event processing MUST be non-blocking
- System MUST support multi-instrument processing

---

# 19. EXTENSIBILITY RULES

- New functionality MUST NOT require modification of existing modules
- Extension points MUST be clearly defined
- Plugins MUST integrate through interfaces only

---

# 20. FINAL RULE

If any implementation violates:

- determinism
- modularity
- separation of concerns
- exit engine independence

It MUST be rejected.

---

# END OF FILE