package com.kbquants.domain;

import lombok.Getter;
import lombok.Setter;

/**
 * Holds the entire state of a trade at any given time.
 * This is the central mutable state object of the engine.
 * <p>
 * basePrice is the cost-inclusive breakeven, not the entry price -- every
 * threshold in the engine is measured from it, so "profit" means money
 * kept after brokerage and taxes.
 */
@Getter
@Setter
public class TradeContext {

    private final String tradeId;
    private final double entryPrice;
    private final double basePrice;
    private final int quantity;
    private final OwnershipMode ownershipMode;

    private Phase currentPhase;
    private double currentStopLoss;

    private boolean forceExited;
    private boolean isClosed;

    public TradeContext(String tradeId, double entryPrice, double basePrice, int quantity, OwnershipMode ownershipMode) {

        this.tradeId = tradeId;
        this.entryPrice = entryPrice;
        this.basePrice = basePrice;
        this.quantity = quantity;
        this.ownershipMode = ownershipMode;

        this.currentPhase = Phase.PHASE_1;
        this.currentStopLoss = 0.0;
        this.forceExited = false;
        this.isClosed = false;
    }
}
