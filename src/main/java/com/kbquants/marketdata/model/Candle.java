package com.kbquants.marketdata.model;


import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class Candle {

    long timestamp;     // epoch millis (UTC)

    double open;
    double high;
    double low;
    double close;

    long volume;

    Long openInterest;  // nullable (not all brokers provide)

    String symbol;      // instrument identifier

    Timeframe timeframe; // 1m, 5m, etc.

}