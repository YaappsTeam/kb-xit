package com.kbquants.domain;

import lombok.Getter;

/**
 * Represents a real-time market price snapshot.
 * This will be used for tick-level processing and hybrid updates.
 */
@Getter
public class MarketSnapshot {

    private final double ltp;
    private final long timestamp;

    public MarketSnapshot(double ltp, long timestamp) {
        this.ltp = ltp;
        this.timestamp = timestamp;
    }
}
