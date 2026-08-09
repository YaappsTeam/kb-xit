package com.kbquants.session;

import lombok.Getter;

import java.util.Objects;

@Getter
public final class TradeFillEvent {

    private final String orderId;
    private final String instrumentKey;
    private final double averagePrice;
    private final int filledQuantity;

    /**
     * The instrument's minimum price increment, or 0 when unknown.
     * <p>
     * Carried on the fill because breakeven has to be rounded up to a price
     * that can actually be traded: on a Rs 1 option premium the tick is
     * Rs 0.05, so a computed breakeven of 1.0253 is not a price anyone can
     * sell at -- the real one is 1.05.
     */
    private final double tickSize;

    /**
     * The trading symbol, for showing the trade to a human. Falls back to
     * the instrument key when unknown -- readable on a Telegram button in a
     * way that "NSE_FO|45148" is not.
     */
    private final String displaySymbol;

    public TradeFillEvent(String orderId, String instrumentKey, double averagePrice, int filledQuantity) {
        this(orderId, instrumentKey, averagePrice, filledQuantity, 0, null);
    }

    public TradeFillEvent(String orderId, String instrumentKey, double averagePrice, int filledQuantity, double tickSize) {
        this(orderId, instrumentKey, averagePrice, filledQuantity, tickSize, null);
    }

    public TradeFillEvent(String orderId, String instrumentKey, double averagePrice, int filledQuantity,
                          double tickSize, String displaySymbol) {
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        this.instrumentKey = Objects.requireNonNull(instrumentKey, "instrumentKey must not be null");
        this.averagePrice = averagePrice;
        this.filledQuantity = filledQuantity;
        this.tickSize = tickSize;
        this.displaySymbol = displaySymbol == null || displaySymbol.isBlank() ? instrumentKey : displaySymbol;
    }
}
