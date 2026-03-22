package com.kbquants.marketdata.aggregation;

import lombok.Getter;

@Getter
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

    public void update(double priceOpen, double priceHigh, double priceLow, double priceClose, long vol) {

        if (!initialized) {
            this.open = priceOpen;
            this.high = priceHigh;
            this.low = priceLow;
            this.close = priceClose;
            this.volume = vol;
            this.initialized = true;
            return;
        }

        this.high = Math.max(this.high, priceHigh);
        this.low = Math.min(this.low, priceLow);
        this.close = priceClose;
        this.volume += vol;
    }
}