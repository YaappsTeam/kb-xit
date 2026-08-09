package com.kbquants.engine;


import com.kbquants.domain.ExitModel;
import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for StopLossEngine.
 * <p>
 * Responsibility:
 * - Validate hard safety behavior
 * - Validate base protection logic
 * - Enforce monotonic SL invariant
 * <p>
 * Does NOT test phase transitions or ownership strategies.
 */
class StopLossEngineTest {

    private StopLossEngine stopLossEngine;
    private TradeContext context;

    @BeforeEach
    void setup() {
        stopLossEngine = new StopLossEngine();

        context = new TradeContext(
                "T1",
                100.0,     // entry price
                101.0,     // base price
                1,
                ExitModel.MODERATE,
                OwnershipMode.CONTINUOUS
        );
    }

    /**
     * Hard safety must be applied immediately.
     * Hard SL = entry - 20%
     */
    @Test
    void shouldApplyHardSafetyStopImmediately() {

        stopLossEngine.applyHardSafety(context);

        assertEquals(80.0, context.getCurrentStopLoss(), 0.0001);
    }

    /**
     * Base protection must set SL to base price
     * when trade is in PHASE_2.
     */
    @Test
    void shouldApplyBaseProtectionInPhase2() {

        context.setCurrentPhase(Phase.PHASE_2);

        stopLossEngine.applyBaseProtectionIfEligible(context);

        assertEquals(101.0, context.getCurrentStopLoss(), 0.0001);
    }

    /**
     * Base protection must NOT apply
     * if trade is not in PHASE_2.
     */
    @Test
    void shouldNotApplyBaseProtectionOutsidePhase2() {

        context.setCurrentPhase(Phase.PHASE_1);

        stopLossEngine.applyBaseProtectionIfEligible(context);

        assertEquals(0.0, context.getCurrentStopLoss());
    }

    /**
     * Stop loss must never move backward.
     */
    @Test
    void stopLossShouldNeverMoveBackward() {

        context.setCurrentStopLoss(105.0);

        stopLossEngine.updateStopLoss(context, 102.0);

        assertEquals(105.0, context.getCurrentStopLoss());
    }

    /**
     * Stop loss should update
     * when candidate is higher than current.
     */
    @Test
    void shouldUpdateStopLossWhenCandidateIsHigher() {

        context.setCurrentStopLoss(105.0);

        stopLossEngine.updateStopLoss(context, 110.0);

        assertEquals(110.0, context.getCurrentStopLoss());
    }

    /**
     * Hard safety must not override
     * a higher stop loss already set.
     */
    @Test
    void hardSafetyShouldNotOverrideHigherStopLoss() {

        context.setCurrentStopLoss(95.0);

        stopLossEngine.applyHardSafety(context);

        // Hard safety = 80
        // Since 80 < 95, SL must remain 95
        assertEquals(95.0, context.getCurrentStopLoss());
    }

    /**
     * Breakeven protection is a floor from PHASE_2 onward, not in PHASE_2
     * alone. Gated on PHASE_2 exactly, a price gapping straight to PHASE_3
     * skipped it -- leaving the stop at the hard stop when it should have
     * been at breakeven.
     */
    @Test
    void baseProtectionShouldStillApplyOnceBeyondPhase2() {

        TradeContext context = new TradeContext(
                "T1", 100.0, 101.0, 1, ExitModel.MODERATE, OwnershipMode.MILESTONE);
        context.setCurrentPhase(Phase.PHASE_3);

        new StopLossEngine().applyBaseProtectionIfEligible(context);

        assertEquals(101.0, context.getCurrentStopLoss(), 0.0001);
    }
}
