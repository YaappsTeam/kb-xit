# PERFORMANCE GUIDELINES
## Efficiency & Scalability Standards

Version: 1.0  
Scope: Performance, Optimization & Scaling  
Audience: Developers, AI Agents  

---

# 1. PURPOSE

This document defines performance requirements to ensure:

- low latency
- efficient computation
- scalability across instruments and strategies

---

# 2. CORE PRINCIPLES

- computation MUST be efficient
- redundant work MUST be avoided
- system MUST scale horizontally
- latency MUST be minimized

---

# 3. INDICATOR PERFORMANCE

---

## 3.1 Streaming Requirement

- streaming indicators MUST operate in O(1)
- updates MUST be constant time

---

## 3.2 Computation Rules

- indicators MUST be computed once per instrument
- results MUST be reused across strategies
- full recomputation MUST be avoided

---

## 3.3 Memory Usage

- state MUST be bounded
- rolling windows MUST have fixed size
- memory leaks MUST be prevented

---

# 4. SCANNER PERFORMANCE

- scanning MUST handle large instrument sets
- evaluation MUST be efficient per instrument
- redundant strategy evaluation MUST be avoided

---

## 4.1 Parallel Processing

- instruments MAY be processed in parallel
- processing MUST remain independent
- shared data MUST be read-only

---

# 5. EVENT SYSTEM PERFORMANCE

- event dispatch MUST be non-blocking
- event handling MUST be asynchronous where possible
- event queues MUST not grow unbounded

---

# 6. TRADE LIFECYCLE PERFORMANCE

- state transitions MUST be lightweight
- state updates MUST be efficient
- trade lookup MUST be fast

---

# 7. EXIT ENGINE PERFORMANCE

- exit evaluation MUST be constant time
- no heavy computation during evaluation
- logic MUST remain lightweight

---

# 8. EXECUTION PERFORMANCE

- order placement MUST be low latency
- execution tracking MUST be efficient
- retries MUST be controlled

---

# 9. MARKET DATA PERFORMANCE

- data ingestion MUST handle high-frequency ticks
- aggregation MUST be efficient
- processing MUST not block data flow

---

# 10. CONCURRENCY GUIDELINES

- processing MUST be thread-safe
- shared mutable state MUST be avoided
- synchronization MUST be minimal

---

# 11. DATA STRUCTURE SELECTION

- use appropriate data structures
- prefer constant-time operations
- avoid unnecessary copying

---

# 12. CACHING RULES

- frequently accessed data MAY be cached
- cache MUST be consistent
- cache invalidation MUST be controlled

---

# 13. BATCH VS STREAMING

- streaming MUST be preferred for live systems
- batch MUST be used for historical processing

---

# 14. LATENCY REQUIREMENTS

- system MUST process events in near real-time
- delays MUST be minimized
- critical paths MUST be optimized

---

# 15. SCALABILITY MODEL

The system MUST scale across:

- instruments  
- strategies  
- event streams  

---

## 15.1 Horizontal Scaling

- scanner MAY be distributed
- event processing MAY be distributed
- modules MUST support scaling

---

# 16. RESOURCE MANAGEMENT

- CPU usage MUST be controlled
- memory usage MUST be predictable
- thread usage MUST be efficient

---

# 17. PERFORMANCE TESTING

System MUST be tested for:

- high load scenarios  
- large instrument sets  
- high event throughput  

---

# 18. FAILURE IMPACT

- performance degradation MUST be controlled
- system MUST remain functional under stress
- failures MUST not cascade

---

# 19. FORBIDDEN PRACTICES

- repeated indicator computation  
- blocking operations in event loop  
- unbounded memory growth  
- unnecessary object creation  
- heavy computation in critical paths  

---

# 20. OPTIMIZATION RULES

- optimize only after correctness
- avoid premature optimization
- focus on critical paths

---

# 21. FINAL RULE

If the system:

- performs redundant computation  
- blocks critical paths  
- fails under load  

It MUST be optimized.

---

# END OF FILE
