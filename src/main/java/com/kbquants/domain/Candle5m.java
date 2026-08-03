package com.kbquants.domain;

import lombok.Getter;

/**
 * Represents a completed 5-minute candle.
 * Used for phase transitions and structured evaluation.
 */
@Getter
public class Candle5m {

    private final double open;
    private final double high;
    private final double low;
    private final double close;
    private final long startTime;
    private final long endTime;

    public Candle5m(double open, double high, double low, double close, long startTime, long endTime) {

        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.startTime = startTime;
        this.endTime = endTime;
    }
}
