package com.kbquants.engine;

import com.kbquants.domain.TradeContext;

public class ExitEngine {

    private final PhaseManager phaseManager;
    private final StopLossEngine stopLossEngine;
    private final OwnershipStrategy ownershipStrategy;

    public ExitEngine(TradeContext context) {

        this.phaseManager = new PhaseManager();
        this.stopLossEngine = new StopLossEngine();
        this.ownershipStrategy = OwnershipStrategyFactory.create(context.getOwnershipMode(), stopLossEngine);
    }

    public void forceExit(TradeContext context, double currentPrice) {

        context.setCurrentStopLoss(currentPrice);
        context.setForceExited(true);
        context.setClosed(true);
    }

    public void onPriceUpdate(double currentPrice, TradeContext context) {

        // Prevent Further Processing After Close
        if (context.isClosed()) {
            return;
        }

        // 1. Always apply hard safety first
        stopLossEngine.applyHardSafety(context);

        // 2. Evaluate phase transitions
        phaseManager.evaluatePhaseTransition(currentPrice, context);

        // 3. Apply base protection
        stopLossEngine.applyBaseProtectionIfEligible(context);

        // 4. Apply ownership logic
        ownershipStrategy.apply(currentPrice, context);
    }
}
