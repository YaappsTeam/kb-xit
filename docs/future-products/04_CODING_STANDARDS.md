# CODING STANDARDS
## Trading Platform Engine Ecosystem

Version: 1.0  
Scope: Code Style & Implementation Standards  
Audience: Developers, AI Agents  

---

# 1. GENERAL PRINCIPLES

- Code MUST be readable, predictable, and maintainable
- Code MUST follow SOLID principles
- Code MUST prioritize clarity over cleverness
- Code MUST be production-ready

---

# 2. CLASS DESIGN RULES

- Each class MUST have a single responsibility
- Classes MUST be small and focused
- Classes MUST avoid mixed responsibilities
- Prefer composition over inheritance

---

# 3. INTERFACE DESIGN RULES

- All core components MUST be defined via interfaces
- Interfaces MUST represent capability, not implementation
- Implementations MUST be replaceable without impact

Naming:

- Use descriptive names without prefixes
- DO NOT use "I" prefix (e.g., no IStrategy)

---

# 4. NAMING CONVENTIONS

## 4.1 Class Names

- Use meaningful nouns
- Use PascalCase

Examples:

IndicatorRegistry  
TradeCoordinator  
ExitStrategyFactory  

Forbidden:

Manager  
Processor  
Handler  
Util (unless justified)

---

## 4.2 Method Names

- MUST start with a verb
- MUST describe exact behavior
- Use camelCase

Examples:

calculateIndicator()  
evaluateCondition()  
executeEntryStrategy()  
updateStopLoss()  
transitionTradeState()  
publishEvent()  

Forbidden:

process()  
handle()  
doWork()  
execute() (without context)

---

## 4.3 Variable Names

- MUST be descriptive
- MUST represent intent clearly
- Use camelCase

Examples:

indicatorValueStore  
entryStrategyRegistry  
instrumentUniverse  
tradeContext  
exitDecision  

Forbidden:

ctx  
tmp  
val  
data  

---

# 5. METHOD DESIGN RULES

- Methods MUST do one thing only
- Methods MUST be short and readable
- Methods MUST avoid deep nesting
- Methods MUST avoid side effects where possible

---

## 5.1 Parameter Rules

- Prefer explicit parameters over hidden state
- Avoid excessive parameters (max 4–5)
- Use objects for grouped parameters

---

## 5.2 Return Values

- MUST return meaningful results
- MUST avoid null where possible
- Use Optional where appropriate

---

# 6. ERROR HANDLING

- Exceptions MUST be meaningful
- DO NOT swallow exceptions
- DO NOT use empty catch blocks

Use:

- IllegalArgumentException for invalid inputs
- IllegalStateException for invalid states

---

# 7. LOGGING STANDARDS

## 7.1 Logger Definition

Every class MUST define:

private static final Logger log =
    LoggerFactory.getLogger(ClassName.class);

---

## 7.2 Logging Rules

- Logs MUST be structured
- Logs MUST include identifiers

Include where applicable:

- tradeId  
- instrument  
- strategyId  
- eventType  

---

## 7.3 Log Levels

INFO  
- major system events  

DEBUG  
- calculations and internal flow  

WARN  
- unexpected but recoverable states  

ERROR  
- failures  

---

## 7.4 Example Logs

log.info("Entry signal generated strategy={} instrument={}", strategyId, instrument);

log.debug("Indicator {} updated value {}", indicatorName, value);

log.warn("Trade {} rejected due to margin constraint", tradeId);

log.error("Order execution failed tradeId={}", tradeId, exception);

---

# 8. LOMBOK USAGE RULES

Allowed:

- Domain models only

Allowed annotations:

@Getter  
@Builder  
@AllArgsConstructor  
@NoArgsConstructor  
@EqualsAndHashCode  
@ToString  

---

Forbidden:

- Core logic classes  
- Engine classes  
- Algorithm classes  

---

# 9. IMMUTABILITY RULES

- Prefer immutable objects
- Fields SHOULD be final where possible
- Avoid setters in domain models

---

# 10. COLLECTION USAGE

- Prefer immutable collections where possible
- Avoid exposing internal collections directly
- Use defensive copying when needed

---

# 11. UTILITY CLASSES

- Utility classes MUST be stateless
- Utility methods MUST be static
- Utility classes MUST NOT contain business logic

---

# 12. ENUM USAGE

- Use enums for fixed sets of values
- Enums MUST have clear naming
- Avoid overloading enums with logic

---

# 13. CODE ORGANIZATION

- Group related logic together
- Maintain clear package structure
- Avoid large classes with multiple responsibilities

---

# 14. COMMENTS AND DOCUMENTATION

- Code SHOULD be self-explanatory
- Use comments only where necessary
- Avoid redundant comments

Use comments for:

- complex logic
- non-obvious decisions

---

# 15. TESTING STANDARDS

- Every class MUST have corresponding tests
- Tests MUST be deterministic
- Tests MUST cover edge cases

---

## 15.1 Unit Tests

- Test single unit of logic
- Mock dependencies where required

---

## 15.2 Naming Tests

MethodName_condition_expectedResult

Example:

calculateRsi_validInput_returnsCorrectValue  

---

# 16. PERFORMANCE PRACTICES

- Avoid unnecessary object creation
- Avoid redundant computations
- Prefer streaming updates over recomputation
- Use efficient data structures

---

# 17. DEPENDENCY MANAGEMENT

- Minimize external dependencies
- Prefer standard library
- Avoid heavy frameworks in core modules

---

# 18. CLEAN CODE RULES

- No magic numbers (use constants)
- No duplicate logic
- No dead code
- No commented-out code blocks

---

# 19. REVIEW CHECKLIST

Before committing code, ensure:

- naming is clear  
- responsibilities are separated  
- no forbidden patterns used  
- logging is present  
- tests are included  

---

# 20. FINAL RULE

If code is:

- unclear  
- ambiguous  
- tightly coupled  
- difficult to test  

It MUST be refactored.

---

# END OF FILE