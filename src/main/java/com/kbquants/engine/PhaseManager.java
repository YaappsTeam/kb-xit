package com.kbquants.engine;

import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;

public class PhaseManager {

    private final double phase2TriggerFraction;
    private final double phase3TriggerFraction;

    public PhaseManager() {
        this(MilestoneLadder.defaultLadder());
    }

    public PhaseManager(MilestoneLadder ladder) {
        this.phase2TriggerFraction = ladder.phaseTriggerFraction(Phase.PHASE_2);
        this.phase3TriggerFraction = ladder.phaseTriggerFraction(Phase.PHASE_3);
    }

    public void evaluatePhaseTransition(double currentPrice, TradeContext context) {

        switch (context.getCurrentPhase()) {

            case PHASE_1 -> handlePhase1(currentPrice, context);

            case PHASE_2 -> handlePhase2(currentPrice, context);

            default -> {
            }
        }
    }

    private void handlePhase1(double currentPrice, TradeContext context) {

        double triggerPrice =
                context.getEntryPrice() * (1 + phase2TriggerFraction);

        if (currentPrice >= triggerPrice) {
            context.setCurrentPhase(Phase.PHASE_2);
        }
    }

    private void handlePhase2(double currentPrice, TradeContext context) {

        double triggerPrice =
                context.getEntryPrice() * (1 + phase3TriggerFraction);

        if (currentPrice >= triggerPrice) {
            context.setCurrentPhase(Phase.PHASE_3);
        }
    }
}
