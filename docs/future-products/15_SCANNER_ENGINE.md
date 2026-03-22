# SCANNER ENGINE
## scanner-engine Module

Version: 1.0  
Scope: Multi-Instrument Strategy Evaluation  
Audience: Developers, AI Agents  

---

# 1. PURPOSE

The scanner engine is responsible for:

- evaluating strategies across instruments
- identifying potential trade opportunities
- generating pre-entry signals

---

# 2. RESPONSIBILITY

The module MUST:

- iterate instrument universe
- evaluate strategies per instrument
- generate entry signal candidates
- prevent duplicate signals

---

# 3. NON-RESPONSIBILITY

The module MUST NOT:

- execute trades
- manage trade lifecycle
- handle exit logic
- interact with broker systems

---

# 4. CORE DESIGN PRINCIPLES

- scanning MUST be efficient
- evaluation MUST be isolated per instrument
- results MUST be deterministic
- indicators MUST be reused

---

# 5. ARCHITECTURE OVERVIEW

The module consists of:

- scanner engine core
- scan session
- instrument pipeline
- signal publisher
- signal deduplicator

---

# 6. PACKAGE STRUCTURE

Base package:

com.kbquants.scanner

Subpackages:

engine  
pipeline  
signal  

---

# 7. INSTRUMENT UNIVERSE

Represents all instruments to be scanned.

Sources:

- predefined list
- dynamic filters
- external configuration

Rules:

- universe MUST be configurable
- instruments MUST be independent

---

# 8. SCAN SESSION

Represents a single scan cycle.

Responsibilities:

- manage scanning state
- coordinate evaluation
- track results

---

# 9. INSTRUMENT PIPELINE

Handles evaluation per instrument.

Responsibilities:

- fetch indicator values
- execute strategy evaluation
- produce signal candidates

Rules:

- each instrument MUST be processed independently
- shared data MUST be reused safely

---

# 10. STRATEGY EVALUATION

- strategies MUST be evaluated using strategy-composition module
- evaluation MUST be deterministic
- no side effects allowed

---

# 11. SIGNAL GENERATION

Scanner produces:

EntrySignalEvent (candidate)

Rules:

- signals MUST represent valid opportunities
- signals MUST not trigger execution directly

---

# 12. SIGNAL DEDUPLICATION

Purpose:

- prevent repeated signals for same condition

Rules:

- duplicate signals MUST be filtered
- deduplication MUST consider:
  - instrument
  - strategy
  - time window

---

# 13. MULTI-STRATEGY SUPPORT

- multiple strategies MUST run simultaneously
- strategies MUST not interfere
- shared indicator data MUST be reused

---

# 14. MULTI-INSTRUMENT SUPPORT

- scanning MUST support large instrument sets
- evaluation MUST scale horizontally
- processing MUST remain independent per instrument

---

# 15. INDICATOR USAGE RULES

- indicators MUST be computed once per instrument
- indicator values MUST be reused across strategies
- no redundant computation allowed

---

# 16. EVENT OUTPUT

Scanner MUST publish:

EntrySignalEvent

This event is consumed by entry engine.

---

# 17. PERFORMANCE RULES

- scanning MUST be efficient
- avoid repeated evaluations
- support concurrent execution
- avoid blocking operations

---

# 18. CONFIGURATION RULES

Scanner MUST support configuration:

- instrument universe
- active strategies
- scan frequency
- deduplication window

Configuration MUST be external.

---

# 19. EXTENSIBILITY RULES

- new scanning strategies MUST be pluggable
- pipeline MUST be extendable
- no hardcoded strategy logic

---

# 20. FORBIDDEN PATTERNS

- direct execution triggering
- exit logic inside scanner
- shared mutable state across instruments
- hardcoded instrument lists
- redundant indicator calculations

---

# 21. TESTING REQUIREMENTS

Scanner MUST be tested for:

- correct strategy evaluation
- multi-instrument handling
- signal generation
- deduplication behavior

---

# 22. FAILURE HANDLING

The module MUST handle:

- missing data
- partial failures
- indicator unavailability

Processing MUST continue for other instruments.

---

# 23. FINAL RULE

If the scanner:

- executes trades  
- manages exits  
- duplicates signals excessively  

It is incorrectly implemented.

---

# END OF FILE
