# PLUGIN GUIDE
## Extension & Integration Guidelines

Version: 1.0  
Scope: Adding New Components to the System  
Audience: Developers, Contributors, Integrators  

---

# 1. PURPOSE

This document defines how to extend the system by adding:

- indicators
- entry strategies
- exit strategies
- broker adapters
- custom modules

---

# 2. EXTENSION PRINCIPLES

All extensions MUST follow:

- interface-based design
- registry-based registration
- zero modification of existing code

---

# 3. EXTENSION TYPES

The system supports the following extensions:

- indicators
- entry strategies
- exit strategies
- broker adapters
- data sources

---

# 4. INDICATOR EXTENSION

---

## 4.1 Steps

1. Create an indicator class  
2. Implement the indicator interface  
3. Define metadata  
4. Register in IndicatorRegistry  

---

## 4.2 Requirements

- MUST follow the stateless algorithm pattern
- MUST separate state from logic
- MUST support configuration

---

## 4.3 Registration

Indicators MUST be registered using:

IndicatorDefinition  
IndicatorRegistry  

---

# 5. ENTRY STRATEGY EXTENSION

---

## 5.1 Steps

1. Create a strategy class  
2. Implement EntryStrategy interface  
3. Define evaluation logic  
4. Register in EntryStrategyRegistry  

---

## 5.2 Requirements

- MUST use the strategy composition system
- MUST not include exit logic
- MUST be deterministic

---

# 6. EXIT STRATEGY EXTENSION

---

## 6.1 Steps

1. Create a strategy class  
2. Implement the ExitStrategy interface  
3. Define exit logic  
4. Register in ExitStrategyRegistry  

---

## 6.2 Requirements

- MUST follow exit engine rules
- MUST enforce stop loss monotonicity
- MUST operate on ExitContext only

---

# 7. BROKER ADAPTER EXTENSION

---

## 7.1 Steps

1. Implement BrokerAdapter interface  
2. Map broker API to system interface  
3. Register in BrokerRegistry  

---

## 7.2 Requirements

- MUST hide broker-specific details
- MUST not affect core logic
- MUST handle errors gracefully

---

# 8. DATA SOURCE EXTENSION

---

## 8.1 Steps

1. Implement MarketDataFeed interface  
2. Provide data stream  
3. Register in the data engine  

---

## 8.2 Requirements

- MUST provide normalised data
- MUST maintain time order
- MUST be reliable

---

# 9. REGISTRY RULES

All extensions MUST:

- be registered in the corresponding registry
- be discoverable dynamically
- not require core code modification

---

# 10. CONFIGURATION RULES

Extensions MUST support configuration.

Examples:

- indicator parameters  
- strategy thresholds  
- broker credentials  

Configuration MUST be external.

---

# 11. DEPENDENCY RULES

Extensions MUST:

- depend only on interfaces
- avoid cross-module dependencies
- remain isolated

---

# 12. VERSIONING RULES

Extensions SHOULD:

- be versioned independently
- support backward compatibility

---

# 13. TESTING REQUIREMENTS

All extensions MUST include:

- unit tests
- edge case handling
- deterministic behavior

---

# 14. DOCUMENTATION REQUIREMENTS

Each extension MUST include:

- purpose
- usage
- configuration details

---

# 15. DEPLOYMENT MODEL

Extensions MAY be:

- part of the core system
- external plugins
- dynamically loaded modules

---

# 16. FORBIDDEN PATTERNS

- modifying core modules
- bypassing the registry system
- embedding hardcoded logic
- accessing internal state directly

---

# 17. FINAL RULE

If an extension:

- requires modification of existing code  
- breaks modularity  
- violates interfaces  

It MUST be rejected.

---

# END OF FILE
