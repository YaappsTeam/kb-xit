package com.kbquants.engine;


import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Locks profit only at defined profit milestones.
 */
public class MilestoneOwnershipStrategy implements OwnershipStrategy {

    private final StopLossEngine stopLossEngine;
    // Ordered milestones (ascending)
    private final Map<Double, Double> milestoneOwnershipMap = new LinkedHashMap<>();

    public MilestoneOwnershipStrategy(StopLossEngine stopLossEngine) {

        this.stopLossEngine = stopLossEngine;

        milestoneOwnershipMap.put(0.13, 0.30);
        milestoneOwnershipMap.put(0.21, 0.50);
        milestoneOwnershipMap.put(0.34, 0.70);
        milestoneOwnershipMap.put(0.55, 0.85);
    }

    @Override
    public void apply(double currentPrice, TradeContext context) {

        if (context.getCurrentPhase() != Phase.PHASE_3) return;

        double entry = context.getEntryPrice();
        double base = context.getBasePrice();

        double profitFromEntryPercent = (currentPrice - entry) / entry;

        double applicableOwnership = 0.0;

        for (Map.Entry<Double, Double> milestone : milestoneOwnershipMap.entrySet()) {
            if (profitFromEntryPercent >= milestone.getKey()) {
                applicableOwnership = milestone.getValue();
            } else break;
        }

        if (applicableOwnership == 0.0) return;

        double openProfit = currentPrice - base;

        if (openProfit <= 0) return;

        double lockedProfit = openProfit * applicableOwnership;
        double candidateSl = base + lockedProfit;

        stopLossEngine.updateStopLoss(context, candidateSl);
    }
}
