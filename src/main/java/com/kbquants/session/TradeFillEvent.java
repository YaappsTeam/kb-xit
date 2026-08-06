package com.kbquants.session;

import lombok.Getter;

import java.util.Objects;

@Getter
public final class TradeFillEvent {

    private final String orderId;
    private final String instrumentKey;
    private final double averagePrice;
    private final int filledQuantity;

    public TradeFillEvent(String orderId, String instrumentKey, double averagePrice, int filledQuantity) {
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        this.instrumentKey = Objects.requireNonNull(instrumentKey, "instrumentKey must not be null");
        this.averagePrice = averagePrice;
        this.filledQuantity = filledQuantity;
    }
}
