# TESTING GUIDELINES
## Quality & Validation Standards

Version: 1.0  
Scope: Testing Strategy & Requirements  
Audience: Developers, QA Engineers, AI Agents  

---

# 1. PURPOSE

This document defines how the system MUST be tested to ensure:

- correctness
- determinism
- reliability
- stability

---

# 2. TESTING PRINCIPLES

- all logic MUST be testable
- tests MUST be deterministic
- tests MUST cover edge cases
- failures MUST be reproducible

---

# 3. TEST TYPES

---

## 3.1 Unit Tests

Purpose:

- validate individual components

Scope:

- indicators
- conditions
- strategies
- exit logic
- utility classes

---

## 3.2 Integration Tests

Purpose:

- validate interaction between modules

Scope:

- indicator pipeline
- strategy evaluation
- scanner flow
- lifecycle transitions
- execution flow

---

## 3.3 Simulation Tests

Purpose:

- validate system behavior under realistic scenarios

Scope:

- full trade lifecycle
- multiple instruments
- multiple strategies

---

## 3.4 Regression Tests

Purpose:

- ensure existing functionality is not broken

Scope:

- previously tested scenarios
- critical system flows

---

# 4. COVERAGE REQUIREMENTS

Minimum expectations:

- unit test coverage ≥ 90%
- critical modules MUST have near 100% coverage

Critical modules:

- exit-engine
- trade-lifecycle
- indicators-engine

---

# 5. UNIT TEST RULES

- test one unit at a time
- isolate dependencies using mocks
- validate expected outputs

---

## 5.1 Naming Convention

MethodName_condition_expectedResult

Example:

calculateEma_validInput_returnsCorrectValue  

---

## 5.2 Required Scenarios

- valid input
- invalid input
- boundary conditions
- insufficient data

---

# 6. INTEGRATION TEST RULES

- test real interactions between modules
- avoid excessive mocking
- validate event flow

---

## 6.1 Required Scenarios

- indicator → strategy → signal flow  
- entry → lifecycle → execution  
- exit → lifecycle → completion  

---

# 7. SIMULATION TEST RULES

- use realistic data
- simulate full trade scenarios
- validate system behavior over time

---

## 7.1 Required Scenarios

- profitable trades  
- losing trades  
- sideways markets  
- high volatility  

---

# 8. EXIT ENGINE TESTING

MUST validate:

- stop loss monotonicity  
- phase transitions  
- ownership behavior  
- forced exit behavior  

---

# 9. TRADE LIFECYCLE TESTING

MUST validate:

- valid transitions  
- invalid transition rejection  
- full lifecycle completion  

---

# 10. INDICATOR TESTING

MUST validate:

- calculation correctness  
- streaming vs batch consistency  
- edge conditions  

---

# 11. SCANNER TESTING

MUST validate:

- multi-instrument handling  
- multi-strategy evaluation  
- signal deduplication  

---

# 12. EXECUTION TESTING

MUST validate:

- order placement  
- order tracking  
- execution modes  

---

# 13. PERFORMANCE TESTING

MUST validate:

- system under load  
- large instrument sets  
- high-frequency updates  

---

# 14. FAILURE TESTING

MUST simulate:

- missing data  
- delayed events  
- order failures  
- partial fills  

System MUST remain stable.

---

# 15. TEST DATA RULES

- test data MUST be realistic
- avoid hardcoded unrealistic values
- use historical data where possible

---

# 16. AUTOMATION RULES

- tests MUST run automatically
- tests MUST be part of CI pipeline
- failures MUST block deployment

---

# 17. DETERMINISM REQUIREMENT

- same input MUST produce same output
- tests MUST not depend on randomness
- time-dependent logic MUST be controlled

---

# 18. FORBIDDEN PRACTICES

- skipping tests for critical logic  
- ignoring failing tests  
- relying on manual testing only  
- using random data without control  

---

# 19. TEST STRUCTURE

Each module MUST include:

- unit test package  
- integration test package  
- test utilities  

---

# 20. FINAL RULE

If a component:

- is not testable  
- behaves non-deterministically  
- lacks coverage  

It MUST be rejected.

---

# END OF FILE
