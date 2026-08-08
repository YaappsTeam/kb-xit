package com.kbquants.engine;

import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;

import java.util.Map;

public class MilestoneOwnershipStrategy implements OwnershipStrategy {

    private final StopLossEngine stopLossEngine;
    private final Map<Double, Double> milestoneOwnershipMap;

    public MilestoneOwnershipStrategy(StopLossEngine stopLossEngine) {
        this(stopLossEngine, MilestoneLadder.defaultLadder());
    }

    public MilestoneOwnershipStrategy(StopLossEngine stopLossEngine, MilestoneLadder ladder) {
        this.stopLossEngine = stopLossEngine;
        this.milestoneOwnershipMap = ladder.ownershipLockMap();
    }

    @Override
    public void apply(double currentPrice, TradeContext context) {

        if (context.getCurrentPhase() != Phase.PHASE_3) return;

        double base = context.getBasePrice();

        // Measured from basePrice (cost-inclusive breakeven) so a lock of
        // "30% of open profit" locks 30% of money actually kept.
        double netProfitPercent = (currentPrice - base) / base;

        double applicableOwnership = 0.0;

        for (Map.Entry<Double, Double> milestone : milestoneOwnershipMap.entrySet()) {
            if (netProfitPercent >= milestone.getKey()) {
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
