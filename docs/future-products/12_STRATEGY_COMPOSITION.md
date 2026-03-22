# STRATEGY COMPOSITION
## strategy-composition Module

Version: 1.0  
Scope: Strategy Definition & Condition System  
Audience: Developers, AI Agents  

---

# 1. PURPOSE

The strategy composition module defines **how trading strategies are expressed**.

It provides:

- condition definitions
- logical composition
- strategy structures

---

# 2. RESPONSIBILITY

The module MUST:

- define condition interfaces
- support logical combinations (AND, OR, NOT)
- build strategy trees
- evaluate conditions using context

---

# 3. NON-RESPONSIBILITY

The module MUST NOT:

- generate entry signals
- manage trade lifecycle
- execute trades
- manage exits

---

# 4. CORE DESIGN PRINCIPLES

- strategies MUST be declarative
- conditions MUST be composable
- evaluation MUST be deterministic
- strategies MUST be reusable

---

# 5. ARCHITECTURE OVERVIEW

The module consists of:

- condition interfaces
- condition implementations
- logical operators
- strategy builders
- metadata system

---

# 6. PACKAGE STRUCTURE

Base package:

com.kbquants.strategy

Subpackages:

condition  
indicatorconditions  
priceconditions  
logical  
builder  
metadata  

---

# 7. CONDITION SYSTEM

## 7.1 Condition Interface

Represents a single evaluatable rule.

Responsibilities:

- accept context
- return boolean or result object

---

## 7.2 Condition Context

Provides data required for evaluation.

Includes:

- indicator values
- price data
- instrument information

---

## 7.3 Condition Result

Represents evaluation outcome.

Includes:

- true/false result
- optional metadata

---

# 8. CONDITION TYPES

---

## 8.1 Indicator Conditions

Operate on indicator values.

Examples:

- indicator greater than value
- indicator less than value
- indicator crossover
- indicator increasing
- indicator decreasing
- indicator moving toward target
- indicator moving away from target

---

## 8.2 Price Conditions

Operate on price values.

Examples:

- price above level
- price below level
- price crossing threshold

---

## 8.3 Composite Conditions

Combine multiple conditions.

Handled via logical operators.

---

# 9. LOGICAL OPERATORS

---

## 9.1 AND Condition

- all child conditions MUST be true

---

## 9.2 OR Condition

- at least one child condition MUST be true

---

## 9.3 NOT Condition

- inverts condition result

---

# 10. CONDITION TREE

Strategies are represented as trees.

Structure:

- root node (logical or single condition)
- child nodes (conditions or logical groups)

---

# 11. STRATEGY DEFINITION

A strategy is a **composition of conditions**.

Rules:

- MUST be declarative
- MUST be independent of execution
- MUST not contain side effects

---

# 12. STRATEGY BUILDER

Responsible for constructing strategies.

Components:

- StrategyBuilder
- ConditionFactory
- StrategyParser

---

## 12.1 Builder Responsibilities

- construct condition trees
- validate structure
- ensure correctness

---

## 12.2 Factory Responsibilities

- create condition instances
- map configuration to condition objects

---

# 13. STRATEGY METADATA

Each strategy MUST define metadata.

Includes:

- name
- description
- parameters
- applicable instruments

---

# 14. CONFIGURATION RULES

Strategies MUST be configurable.

Examples:

- indicator periods
- threshold values
- condition parameters

Configuration MUST be external.

---

# 15. EVALUATION RULES

- evaluation MUST be deterministic
- evaluation MUST not mutate state
- evaluation MUST depend only on context

---

# 16. REUSABILITY RULES

- conditions MUST be reusable
- strategies MUST be reusable across instruments
- no hardcoded values inside logic

---

# 17. PERFORMANCE RULES

- evaluation MUST be efficient
- avoid redundant condition checks
- reuse computed indicator values

---

# 18. EXTENSIBILITY RULES

- new conditions MUST be added via factory
- no modification of existing conditions
- logical operators MUST remain generic

---

# 19. FORBIDDEN PATTERNS

- embedding execution logic in conditions
- accessing external systems
- mutating shared state
- hardcoding strategy logic in engine
- tight coupling with entry or exit modules

---

# 20. TESTING REQUIREMENTS

Conditions MUST be tested for:

- correct evaluation
- boundary conditions
- logical combinations

Strategies MUST be tested for:

- correct tree evaluation
- deterministic output

---

# 21. FINAL RULE

If a strategy:

- is not declarative  
- has side effects  
- depends on execution  

It MUST be rejected.

---

# END OF FILE
