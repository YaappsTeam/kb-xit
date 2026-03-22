# INDICATORS ENGINE
## indicators-engine Module

Version: 1.0  
Scope: Indicator Computation Architecture  
Audience: Developers, AI Agents  

---

# 1. PURPOSE

The indicators engine is responsible for:

- computing technical indicators
- maintaining indicator state
- providing reusable indicator values

---

# 2. RESPONSIBILITY

The module MUST:

- compute indicators
- support streaming updates
- support batch calculations
- share indicator values across strategies

---

# 3. NON-RESPONSIBILITY

The module MUST NOT:

- contain trading logic
- generate entry signals
- manage trade lifecycle
- interact with execution systems

---

# 4. CORE DESIGN PRINCIPLES

- indicators MUST be reusable
- indicators MUST be deterministic
- computation MUST be efficient
- state MUST be isolated from logic

---

# 5. ARCHITECTURE OVERVIEW

The engine consists of:

- indicator interfaces
- computation algorithms
- state management
- streaming indicators
- batch indicators
- pipeline system
- registry system

---

# 6. PACKAGE STRUCTURE

Base package:

com.kbquants.indicators

Subpackages:

api  
algorithms  
state  
batch  
streaming  
pipeline  
graph  
registry  
metadata  

---

# 7. INDICATOR TYPES

## 7.1 Batch Indicators

- operate on historical data
- compute values from candle lists
- used for research and backtesting

---

## 7.2 Streaming Indicators

- operate on incremental updates
- update with each new data point
- used for live systems

---

# 8. INDICATOR INTERFACES

## 8.1 Batch Indicator

Responsibilities:

- accept historical input
- return computed value

---

## 8.2 Streaming Indicator

Responsibilities:

- accept incremental updates
- maintain internal state
- provide current value

---

# 9. STATE MANAGEMENT

- indicator state MUST be separate from algorithm
- state MUST be encapsulated
- state MUST NOT be shared across instruments

Examples:

- rolling window
- previous values
- internal buffers

---

# 10. ALGORITHM RULES

- algorithms MUST be stateless
- algorithms MUST not store state
- algorithms MUST be reusable

Examples:

- EMA calculation logic
- RSI formula computation
- ATR computation

---

# 11. STREAMING RULES

- streaming indicators MUST operate in O(1)
- update MUST be constant time
- full recomputation MUST be avoided

---

# 12. PIPELINE SYSTEM

The pipeline manages multiple indicators together.

Responsibilities:

- update all indicators per tick
- maintain indicator value store
- ensure execution order

---

## 12.1 Pipeline Behavior

- all indicators update together
- shared inputs are reused
- results are stored centrally

---

# 13. INDICATOR VALUE STORE

- stores latest indicator values
- accessible across modules
- keyed by instrument and indicator

---

# 14. DEPENDENCY GRAPH

Indicators may depend on other indicators.

Examples:

ATR → Supertrend

Rules:

- dependencies MUST be resolved before execution
- execution order MUST be maintained
- no circular dependencies allowed

---

# 15. REGISTRY SYSTEM

All indicators MUST be registered.

Components:

- IndicatorRegistry
- IndicatorFactory
- IndicatorDefinition

---

## 15.1 Registration Rules

- indicators MUST be registered via registry
- creation MUST be dynamic
- configuration MUST be externalized

---

## 15.2 Factory Rules

- factory MUST create indicators using definitions
- factory MUST not contain hardcoded logic

---

# 16. METADATA SYSTEM

Each indicator MUST define metadata.

Includes:

- name
- description
- category
- parameters

---

# 17. SUPPORTED INDICATORS (INITIAL)

- SMA
- EMA
- RSI
- ATR

---

# 18. FUTURE INDICATORS

- MACD
- Bollinger Bands
- VWAP
- Supertrend
- ADX

Architecture MUST support adding these without modification.

---

# 19. SHARING RULES

- indicators MUST be computed once per instrument
- results MUST be shared across strategies
- duplicate computation MUST be avoided

---

# 20. CONFIGURATION RULES

Indicators MUST support configuration:

- period
- source (price type)
- custom parameters

Configuration MUST NOT be hardcoded.

---

# 21. PERFORMANCE RULES

- streaming MUST be O(1)
- memory usage MUST be bounded
- pipeline MUST avoid redundant work

---

# 22. TESTING REQUIREMENTS

Indicators MUST be tested for:

- correctness
- boundary conditions
- insufficient data handling
- invalid parameters

---

# 23. FORBIDDEN PATTERNS

- mixing state and algorithm
- recalculating full history per tick
- hardcoded indicator creation
- cross-module dependencies
- mutable shared state

---

# 24. FINAL RULE

If an indicator:

- is not reusable  
- is not deterministic  
- is not efficient  

It MUST be rejected.

---

# END OF FILE
