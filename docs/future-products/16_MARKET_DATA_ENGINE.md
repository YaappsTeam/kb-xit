# MARKET DATA ENGINE
## market-data-engine Module

Version: 1.0  
Scope: Market Data Ingestion & Aggregation  
Audience: Developers, AI Agents  

---

# 1. PURPOSE

The market data engine is responsible for:

- receiving market data
- normalizing data
- aggregating candles
- publishing data events

---

# 2. RESPONSIBILITY

The module MUST:

- ingest tick-level data
- aggregate ticks into candles
- maintain instrument data streams
- publish market events

---

# 3. NON-RESPONSIBILITY

The module MUST NOT:

- compute indicators
- evaluate strategies
- generate signals
- execute trades

---

# 4. CORE DESIGN PRINCIPLES

- data MUST be accurate
- data MUST be time-ordered
- aggregation MUST be deterministic
- data flow MUST be continuous

---

# 5. ARCHITECTURE OVERVIEW

The module consists of:

- market data feed interfaces
- data normalization layer
- candle aggregation
- universe management
- event publishing

---

# 6. PACKAGE STRUCTURE

Base package:

com.kb.trading.marketdata

Subpackages:

feed  
aggregation  
universe  

---

# 7. DATA SOURCES

Market data may come from:

- live feeds
- historical data
- simulation sources

---

## 7.1 Feed Types

- LiveFeed  
- HistoricalFeed  
- SimulationFeed  

All feeds MUST implement a common interface.

---

# 8. TICK DATA

Represents raw market updates.

Includes:

- timestamp
- price
- volume

Rules:

- ticks MUST be processed in order
- missing ticks MUST be handled gracefully

---

# 9. CANDLE AGGREGATION

Ticks are aggregated into candles.

---

## 9.1 Candle Structure

- open  
- high  
- low  
- close  
- volume  
- timestamp  

---

## 9.2 Aggregation Rules

- aggregation MUST be time-based
- each timeframe MUST produce consistent candles
- partial candles MUST be handled correctly

---

## 9.3 Supported Timeframes

- 1 minute  
- 3 minute  
- 5 minute  
- 15 minute  
- 1 hour  
- 1 day  

---

# 10. TIMEFRAME MANAGEMENT

- timeframe logic MUST be consistent
- bucket calculation MUST be deterministic
- candle boundaries MUST not overlap

---

# 11. DATA NORMALIZATION

- incoming data MUST be normalized
- data format MUST be consistent
- invalid data MUST be rejected or corrected

---

# 12. INSTRUMENT UNIVERSE

Represents all active instruments.

Responsibilities:

- maintain list of instruments
- support filtering
- support dynamic updates

---

# 13. EVENT OUTPUT

The module MUST publish:

MarketTickEvent  
CandleEvent (optional)  

Events MUST contain:

- instrument  
- timestamp  
- price data  

---

# 14. DATA DISTRIBUTION

- events MUST be broadcast to subscribers
- multiple modules MUST consume same data
- data MUST not be duplicated unnecessarily

---

# 15. PERFORMANCE RULES

- data ingestion MUST be low latency
- aggregation MUST be efficient
- memory usage MUST be bounded
- system MUST support high-frequency updates

---

# 16. CONCURRENCY RULES

- data processing MUST be thread-safe
- instrument streams MUST be isolated
- race conditions MUST be avoided

---

# 17. CONFIGURATION RULES

Market data engine MUST support configuration:

- data source selection  
- timeframes  
- aggregation settings  

Configuration MUST be external.

---

# 18. FAILURE HANDLING

The module MUST handle:

- missing data
- delayed data
- feed disconnections

Behavior:

- continue processing where possible
- avoid system-wide failure

---

# 19. EXTENSIBILITY RULES

- new data sources MUST be pluggable
- feed implementations MUST follow interfaces
- aggregation logic MUST remain generic

---

# 20. FORBIDDEN PATTERNS

- strategy logic inside data engine
- indicator computation inside data engine
- direct dependency on other modules
- hardcoded data sources

---

# 21. TESTING REQUIREMENTS

The module MUST be tested for:

- correct aggregation
- timeframe boundaries
- data ordering
- missing data handling

---

# 22. FINAL RULE

If the market data engine:

- produces inconsistent candles  
- breaks time ordering  
- mixes business logic  

It MUST be rejected.

---

# END OF FILE
