package com.kbquants.engine;

import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.TradeContext;

public class ExitEngine {

    private final PhaseManager phaseManager;
    private final StopLossEngine stopLossEngine;
    private final OwnershipStrategy ownershipStrategy;

    public ExitEngine(TradeContext context) {
        this(context, MilestoneLadder.defaultLadder());
    }

    public ExitEngine(TradeContext context, MilestoneLadder ladder) {
        this.phaseManager = new PhaseManager(ladder);
        this.stopLossEngine = new StopLossEngine(ladder.getHardStopPercent());
        this.ownershipStrategy = OwnershipStrategyFactory.create(context.getOwnershipMode(), stopLossEngine, ladder);
    }

    public void forceExit(TradeContext context, double currentPrice) {

        context.setCurrentStopLoss(currentPrice);
        context.setForceExited(true);
        context.setClosed(true);
    }

    public void onPriceUpdate(double currentPrice, TradeContext context) {

        if (context.isClosed()) {
            return;
        }

        stopLossEngine.applyHardSafety(context);
        phaseManager.evaluatePhaseTransition(currentPrice, context);
        stopLossEngine.applyBaseProtectionIfEligible(context);
        ownershipStrategy.apply(currentPrice, context);
    }
}
