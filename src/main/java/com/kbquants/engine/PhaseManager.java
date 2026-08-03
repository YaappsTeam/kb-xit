package com.kbquants.engine;


import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;

/**
 * Handles phase transitions for a trade.
 * V0 Rules:
 * PHASE_1 -> PHASE_2 at +6%
 * PHASE_2 -> PHASE_3 at +13%
 */
public class PhaseManager {

    private static final double PHASE2_TRIGGER_PERCENT = 0.06; // 6%
    private static final double PHASE3_TRIGGER_PERCENT = 0.13; // 13%

    public void evaluatePhaseTransition(double currentPrice, TradeContext context) {

        switch (context.getCurrentPhase()) {

            case PHASE_1 -> handlePhase1(currentPrice, context);

            case PHASE_2 -> handlePhase2(currentPrice, context);

            default -> {
                // No transitions for now
            }
        }
    }

    private void handlePhase1(double currentPrice, TradeContext context) {

        double triggerPrice =
                context.getEntryPrice() * (1 + PHASE2_TRIGGER_PERCENT);

        if (currentPrice >= triggerPrice) {
            context.setCurrentPhase(Phase.PHASE_2);
        }
    }

    private void handlePhase2(double currentPrice, TradeContext context) {

        double triggerPrice =
                context.getEntryPrice() * (1 + PHASE3_TRIGGER_PERCENT);

        if (currentPrice >= triggerPrice) {
            context.setCurrentPhase(Phase.PHASE_3);
        }
    }
}
