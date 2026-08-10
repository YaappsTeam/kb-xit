package com.kbquants.engine;


import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;

/**
 * Handles stop-loss updates.
 * V0: Implements Base Protection when Phase 2 is reached.
 */
public class StopLossEngine {

    private static final double DEFAULT_HARD_SAFETY_PERCENT = 0.20; // 20%

    private final double hardSafetyPercent;

    public StopLossEngine() {
        this(DEFAULT_HARD_SAFETY_PERCENT);
    }

    /**
     * The hard stop comes from the active milestone ladder, because it has
     * to match the instrument class: 20% below entry is a disaster stop on
     * an equity and a routine wiggle on an option premium.
     */
    public StopLossEngine(double hardSafetyPercent) {
        if (hardSafetyPercent <= 0 || hardSafetyPercent >= 1.0) {
            throw new IllegalArgumentException("hardSafetyPercent must be between 0 and 1: " + hardSafetyPercent);
        }
        this.hardSafetyPercent = hardSafetyPercent;
    }

    public void applyHardSafety(TradeContext context) {
        double hardSl = context.getEntryPrice() * (1 - hardSafetyPercent);
        updateStopLoss(context, hardSl);
    }

    /**
     * From PHASE_2 <b>onward</b>, not PHASE_2 alone: once capital has been
     * protected it stays protected.
     * <p>
     * Gated on PHASE_2 exactly, a price gapping straight through to PHASE_3
     * in one tick skipped breakeven protection entirely -- and if the
     * ownership lock did not apply that tick, the stop stayed at the hard
     * stop when it should have been at breakeven. The write ratchets, so
     * re-applying it in later phases can only ever be a no-op.
     */
    public void applyBaseProtectionIfEligible(TradeContext context) {
        if (context.getCurrentPhase().ordinal() >= Phase.PHASE_2.ordinal()) {
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
