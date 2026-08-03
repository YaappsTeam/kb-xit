package com.kbquants.domain;

import lombok.Getter;
import lombok.Setter;

/**
 * Holds the entire state of a trade at any given time.
 * This is the central mutable state object of the engine.
 */
@Getter
@Setter
public class TradeContext {

    private final String tradeId;
    private final double entryPrice;
    private final double basePrice;
    private final int quantity;
    private final ExitModel exitModel;
    private final OwnershipMode ownershipMode;

    private Phase currentPhase;
    private double currentStopLoss;

    private double atr;
    private double ownershipPercentage;
    private int hybridUpdateCount;
    private boolean hybridEnabled;
    private boolean forceExited;
    private boolean isClosed;

    public TradeContext(String tradeId, double entryPrice, double basePrice, int quantity, ExitModel exitModel, OwnershipMode ownershipMode) {

        this.tradeId = tradeId;
        this.entryPrice = entryPrice;
        this.basePrice = basePrice;
        this.quantity = quantity;
        this.exitModel = exitModel;
        this.ownershipMode = ownershipMode;

        this.currentPhase = Phase.PHASE_1;
        this.currentStopLoss = 0.0;
        this.atr = 0.0;
        this.ownershipPercentage = 0.0;
        this.hybridUpdateCount = 0;
        this.hybridEnabled = false;
        this.forceExited = false;
        this.isClosed = false;
    }
}
