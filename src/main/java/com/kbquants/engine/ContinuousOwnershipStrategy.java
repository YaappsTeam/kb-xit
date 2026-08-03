package com.kbquants.engine;


import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;

/**
 * Continuously locks a fixed percentage of open profit.
 */
public class ContinuousOwnershipStrategy implements OwnershipStrategy {

    private static final double OWNERSHIP_PERCENT = 0.30; // 30%
    private final StopLossEngine stopLossEngine;

    public ContinuousOwnershipStrategy(StopLossEngine stopLossEngine) {
        this.stopLossEngine = stopLossEngine;
    }

    @Override
    public void apply(double currentPrice, TradeContext context) {
        if (context.getCurrentPhase() != Phase.PHASE_3) return;

        double base = context.getBasePrice();
        double openProfit = currentPrice - base;

        if (openProfit <= 0) return;

        double lockedProfit = openProfit * OWNERSHIP_PERCENT;
        double candidateSl = base + lockedProfit;

        stopLossEngine.updateStopLoss(context, candidateSl);
    }
}
