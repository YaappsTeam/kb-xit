package com.kbquants.session;

import lombok.Getter;

import java.util.Objects;

/**
 * A position as the broker reports it, not as this system remembers it.
 * <p>
 * Deliberately a different type from {@link TradeFillEvent}: a fill is
 * something that happened once, a position is what is open right now.
 * Reconciliation exists precisely because the two can disagree.
 */
@Getter
public final class BrokerPosition {

    private final String instrumentKey;

    /**
     * Net quantity: positive long, negative short, zero for a position
     * closed today but still listed. This system manages long positions
     * only -- it sells what was bought elsewhere -- so anything not
     * positive is not something it can act on.
     */
    private final int quantity;

    private final double averagePrice;
    private final String displaySymbol;

    /** "I" for intraday, "D" for delivery, as the broker labels it. */
    private final String product;

    public BrokerPosition(String instrumentKey, int quantity, double averagePrice,
                          String displaySymbol, String product) {
        this.instrumentKey = Objects.requireNonNull(instrumentKey, "instrumentKey must not be null");
        this.quantity = quantity;
        this.averagePrice = averagePrice;
        this.displaySymbol = displaySymbol == null || displaySymbol.isBlank() ? instrumentKey : displaySymbol;
        this.product = product == null || product.isBlank() ? "?" : product;
    }

    public boolean isLong() {
        return quantity > 0;
    }
}
