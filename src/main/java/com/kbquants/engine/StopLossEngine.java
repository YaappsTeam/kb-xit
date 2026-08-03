package com.kbquants.engine;


import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;

/**
 * Handles stop-loss updates.
 * V0: Implements Base Protection when Phase 2 is reached.
 */
public class StopLossEngine {

    private static final double HARD_SAFETY_PERCENT = 0.20; // 20%

    public void applyHardSafety(TradeContext context) {
        double hardSl = context.getEntryPrice() * (1 - HARD_SAFETY_PERCENT);
        updateStopLoss(context, hardSl);
    }

    public void applyBaseProtectionIfEligible(TradeContext context) {
        if (context.getCurrentPhase() == Phase.PHASE_2) {
            updateStopLoss(context, context.getBasePrice());
        }
    }

    /**
     * Ensures SL never moves backward.
     */
    public void updateStopLoss(TradeContext context, double candidateSl) {
        double currentSl = context.getCurrentStopLoss();
        if (candidateSl > currentSl) {
            context.setCurrentStopLoss(candidateSl);
        }
    }
}
