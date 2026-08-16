# EXIT ENGINE
## exit-engine Module

Version: 1.0  
Scope: Exit Logic & Risk Management  
Audience: Developers, AI Agents  

---

# 1. PURPOSE

The exit engine is responsible for:

- managing active trades
- enforcing risk discipline
- protecting capital
- controlling exit behavior

---

# 2. RESPONSIBILITY

The module MUST:

- update stop loss
- enforce capital protection
- manage profit ownership
- trigger exits
- enforce end-of-day closure

---

# 3. NON-RESPONSIBILITY

The module MUST NOT:

- generate entry signals
- depend on entry logic
- perform market prediction
- interact with broker implementations

---

# 4. CORE DESIGN PRINCIPLES

- exit logic MUST be deterministic
- stop loss MUST be monotonic
- behavior MUST be phase-based
- logic MUST be independent of entry

---

# 5. ARCHITECTURE OVERVIEW

The module consists of:

- ExitStrategy interface
- ExitContext
- StopLossEngine
- Ownership strategies
- Phase management
- Exit orchestrator

---

# 6. PACKAGE STRUCTURE

Base package:

com.kbquants.exit

Subpackages:

api  
strategies  
risk  
registry  

---

# 7. EXIT CONTEXT

Represents full trade state.

Includes:

- entry price
- current price
- stop loss
- trade phase
- profit state
- configuration

All exit logic MUST operate using this context only.

---

# 8. TRADE PHASE MODEL

Trades MUST progress through phases.

---

## 8.1 Phase 1 — Initial Risk

Purpose:

- validate trade quickly

Behavior:

- initial stop loss active
- losses allowed within defined limits

---

## 8.2 Phase 2 — Capital Protection

Trigger:

- predefined profit threshold

Behavior:

- stop loss moves to base level
- capital and costs protected

---

## 8.3 Phase 3 — Profit Ownership

Trigger:

- higher profit threshold

Behavior:

- ownership strategy activated
- profit becomes protected progressively

---

## 8.4 Phase 4 — Advanced Protection (Future)

Behavior:

- aggressive protection
- minimal giveback allowed

---

# 9. STOP LOSS ENGINE

Responsible for stop loss management.

Rules:

- stop loss MUST only tighten
- stop loss MUST never decrease
- stop loss MUST respect phase rules

---

# 10. HARD SAFETY

Absolute loss limit.

Rules:

- MUST always be active
- MUST override other logic if needed
- MUST never be bypassed

---

# 11. OWNERSHIP STRATEGY

Defines how profit is protected.

Types:

- Continuous ownership
- Milestone-based ownership

Rules:

- MUST activate only after capital protection
- MUST progressively lock profit
- MUST not reduce stop loss

---

# 12. EXIT STRATEGY INTERFACE

Defines exit evaluation contract.

Responsibilities:

- evaluate exit condition
- return ExitDecision

---

# 13. EXIT DECISION

Represents exit action.

Contains:

- exit trigger
- reason
- price reference

---

# 14. EXIT TRIGGERS

Exit can be triggered by:

- stop loss hit
- ownership protection
- time-based exit
- manual override

---

# 15. TIME-BASED EXIT

Rules:

- all trades MUST close before end of day
- time exit MUST override all logic

---

# 16. MANUAL EXIT

Rules:

- MUST always be allowed
- MUST immediately trigger exit
- MUST not be blocked by system logic

---

# 17. HYBRID LOGIC (OPTIONAL)

Purpose:

- accelerate profit protection

Rules:

- allowed only after profit ownership begins
- MUST only tighten stop loss
- MUST not introduce new signals

---

# 18. REGISTRY SYSTEM

Exit strategies MUST be registered.

Components:

- ExitStrategyRegistry
- ExitStrategyFactory

Rules:

- no hardcoded strategy creation
- all strategies MUST be pluggable

---

# 19. CONFIGURATION RULES

Exit behavior MUST be configurable.

Includes:

- phase thresholds
- ownership parameters
- hard safety limits

Configuration MUST be external.

---

# 20. STATE MANAGEMENT RULES

- all state MUST reside in TradeContext
- engine MUST be single-trade scoped
- no shared mutable state

---

# 21. PERFORMANCE RULES

- exit evaluation MUST be efficient
- no redundant calculations
- evaluation MUST be constant time where possible

---

# 22. FORBIDDEN PATTERNS

- stop loss reduction
- dynamic logic changes mid-trade
- dependency on entry engine
- predictive logic
- machine learning in core logic
- broker-specific logic

---

# 23. TESTING REQUIREMENTS

Exit engine MUST be tested for:

- phase transitions
- stop loss monotonicity
- ownership behavior
- forced exits
- edge conditions

---

# 24. FAILURE HANDLING

The engine MUST handle:

- missing data gracefully
- execution delays
- unexpected price movement

State MUST remain consistent.

---

# 25. FINAL RULE

If the exit engine:

- reduces stop loss  
- depends on entry logic  
- behaves non-deterministically  

It MUST be rejected.

---

# END OF FILE
