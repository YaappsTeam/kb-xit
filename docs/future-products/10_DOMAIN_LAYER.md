# DOMAIN LAYER
## trading-domain Module

Version: 1.0  
Scope: Data Models Only  
Audience: Developers, AI Agents  

---

# 1. PURPOSE

The domain layer defines **core data models** used across the system.

It represents:

- system state
- trading entities
- event payloads

---

# 2. RESPONSIBILITY

The domain layer is responsible for:

- representing data
- enforcing basic data integrity
- providing shared models across modules

---

# 3. NON-RESPONSIBILITY

The domain layer MUST NOT:

- contain business logic
- perform calculations
- depend on external modules
- interact with infrastructure

---

# 4. CORE DESIGN PRINCIPLES

- models MUST be simple
- models MUST be predictable
- models SHOULD be immutable
- models MUST be reusable across modules

---

# 5. PACKAGE STRUCTURE

Base package:

com.kbquants.domain

Subpackages:

instrument  
market  
trade  
order  
strategy  
indicator  
event  
common  

---

# 6. MODEL CATEGORIES

---

## 6.1 instrument

Represents tradable entities.

Examples:

Instrument  
InstrumentType  
Exchange  
AssetClass  
OptionContract  
FutureContract  

---

## 6.2 market

Represents market data.

Examples:

Candle  
Tick  
MarketSnapshot  
PriceLevel  
VolumeData  

---

## 6.3 trade

Represents trade state.

Examples:

TradeContext  
TradeState  
TradeSide  
TradePhase  
Position  

---

## 6.4 order

Represents execution entities.

Examples:

OrderRequest  
OrderResponse  
OrderStatus  
ExecutionReport  
Fill  

---

## 6.5 strategy

Represents strategy-level outputs.

Examples:

EntrySignal  
ExitDecision  
StrategyMetadata  
StrategyScope  

---

## 6.6 indicator

Represents indicator data.

Examples:

IndicatorKey  
IndicatorValue  
IndicatorState  
IndicatorDependency  

---

## 6.7 event

Represents system events.

Examples:

MarketTickEvent  
IndicatorUpdatedEvent  
EntrySignalEvent  
TradeOpenedEvent  
PriceUpdateEvent  
ExitTriggeredEvent  
OrderFilledEvent  
TradeCompletedEvent  

---

## 6.8 common

Reusable value objects.

Examples:

Money  
Percentage  
TimeRange  
Direction  

---

# 7. MODEL DESIGN RULES

- All models MUST be simple POJOs
- Fields SHOULD be final where possible
- Constructors MUST validate required fields
- No setter-based mutation (prefer immutability)

---

# 8. IMMUTABILITY RULES

Preferred:

- final fields
- constructor initialization
- no setters

Allowed exceptions:

- builder pattern
- framework constraints

---

# 9. VALIDATION RULES

Models MAY include:

- null checks
- basic value validation

Models MUST NOT include:

- business rules
- cross-object logic
- external calls

---

# 10. DEPENDENCY RULES

Domain layer MUST NOT depend on:

- indicators-engine
- entry-engine
- exit-engine
- execution-infrastructure
- any external system

Domain layer MAY depend on:

- Java standard library
- Lombok (for models)

---

# 11. EVENT MODEL RULES

Events MUST:

- be immutable
- contain all required data
- represent a single occurrence

Events MUST NOT:

- contain logic
- reference services
- mutate state

---

# 12. ENUM RULES

Enums MUST:

- represent fixed values only
- be clearly named
- avoid embedded logic

Examples:

TradeState  
OrderStatus  
Direction  

---

# 13. VALUE OBJECT RULES

Value objects MUST:

- be immutable
- encapsulate simple concepts

Examples:

Money  
Percentage  

---

# 14. SERIALIZATION RULES

Models MUST be:

- serializable
- transport-friendly

Used for:

- event passing
- persistence
- API communication

---

# 15. NAMING RULES

Model names MUST:

- reflect domain meaning
- be unambiguous

Examples:

TradeContext  
EntrySignal  
ExitDecision  

---

# 16. FORBIDDEN PATTERNS

- business logic inside models
- service references inside models
- static mutable state
- dependency injection in models
- complex inheritance hierarchies

---

# 17. TESTING REQUIREMENTS

Models MUST be tested for:

- construction validity
- validation rules
- immutability behavior

---

# 18. FINAL RULE

If a class:

- performs logic  
- depends on other modules  
- contains behavior  

It DOES NOT belong in the domain layer.

---

# END OF FILE
