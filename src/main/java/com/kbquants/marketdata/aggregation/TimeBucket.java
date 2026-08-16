package com.kbquants.marketdata.aggregation;

/**
 * Mutable accumulator for a single time bucket while a candle is being formed.
 *
 * <p>This is engine state, not a domain model, so Lombok is intentionally not
 * used here (per coding standards, Lombok is restricted to domain models).
 */
public class TimeBucket {

    private final long bucketStart;

    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;

    private boolean initialized = false;

    public TimeBucket(long bucketStart) {
        this.bucketStart = bucketStart;
    }

    public void update(double priceOpen, double priceHigh, double priceLow, double priceClose, long incomingVolume) {

        if (!initialized) {
            this.open = priceOpen;
            this.high = priceHigh;
            this.low = priceLow;
            this.close = priceClose;
            this.volume = incomingVolume;
            this.initialized = true;
            return;
        }

        this.high = Math.max(this.high, priceHigh);
        this.low = Math.min(this.low, priceLow);
        this.close = priceClose;
        this.volume += incomingVolume;
    }

    public long getBucketStart() {
        return bucketStart;
    }

    public double getOpen() {
        return open;
    }

    public double getHigh() {
        return high;
    }

    public double getLow() {
        return low;
    }

    public double getClose() {
        return close;
    }

    public long getVolume() {
        return volume;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
