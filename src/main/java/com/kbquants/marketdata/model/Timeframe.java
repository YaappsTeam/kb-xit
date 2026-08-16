package com.kbquants.marketdata.model;

public enum Timeframe {

    ONE_MIN(60_000),
    THREE_MIN(3 * 60_000),
    FIVE_MIN(5 * 60_000),
    FIFTEEN_MIN(15 * 60_000),
    ONE_HOUR(60 * 60_000),
    ONE_DAY(24 * 60 * 60_000);

    private final long durationMillis;

    Timeframe(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public long getBucketStart(long timestamp) {
        return (timestamp / durationMillis) * durationMillis;
    }

    public boolean isIntraday() {
        return this != ONE_DAY;
    }
}