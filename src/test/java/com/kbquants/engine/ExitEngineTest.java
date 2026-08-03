package com.kbquants.engine;


import com.kbquants.domain.ExitModel;
import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for ExitEngine.
 * <p>
 * Validates:
 * - Hard safety application order
 * - Phase transitions
 * - Base protection
 * - Ownership activation
 * - Force exit behavior
 * <p>
 * Does NOT test session layer.
 */
class ExitEngineTest {

    /**
     * Hard safety must be applied immediately
     * even before phase transitions.
     */
    @Test
    void shouldApplyHardSafetyBeforePhaseLogic() {

        TradeContext context = new TradeContext("T1", 100.0, 101.0, 1,
                ExitModel.MODERATE, OwnershipMode.CONTINUOUS);

        ExitEngine engine = new ExitEngine(context);

        engine.onPriceUpdate(95.0, context);

        // Hard SL = 80
        assertEquals(80.0, context.getCurrentStopLoss(), 0.0001);
    }

    /**
     * Full lifecycle:
     * Phase 1 → Phase 2 → Phase 3
     */
    @Test
    void shouldMoveThroughAllPhasesCorrectly() {

        TradeContext context = new TradeContext("T1", 100.0, 101.0, 1,
                ExitModel.MODERATE, OwnershipMode.CONTINUOUS);

        ExitEngine engine = new ExitEngine(context);
        assertEquals(Phase.PHASE_1, context.getCurrentPhase());

        engine.onPriceUpdate(106.0, context); // +6%
        assertEquals(Phase.PHASE_2, context.getCurrentPhase());

        engine.onPriceUpdate(113.0, context); // +13%
        assertEquals(Phase.PHASE_3, context.getCurrentPhase());
    }

    /**
     * Base protection must apply in Phase 2.
     */
    @Test
    void shouldApplyBaseProtectionInPhase2() {

        TradeContext context = new TradeContext("T1", 100.0, 101.0, 1,
                ExitModel.MODERATE, OwnershipMode.CONTINUOUS);

        ExitEngine engine = new ExitEngine(context);

        engine.onPriceUpdate(106.0, context);

        assertEquals(101.0, context.getCurrentStopLoss());
    }

    /**
     * Continuous ownership should apply correctly in Phase 3.
     */
    @Test
    void shouldApplyContinuousOwnershipCorrectly() {

        TradeContext context = new TradeContext("T1", 100.0, 101.0, 1,
                ExitModel.MODERATE, OwnershipMode.CONTINUOUS);

        ExitEngine engine = new ExitEngine(context);

        engine.onPriceUpdate(106.0, context);
        engine.onPriceUpdate(113.0, context);
        engine.onPriceUpdate(120.0, context);

        assertEquals(106.7, context.getCurrentStopLoss(), 0.0001);
    }

    /**
     * Milestone ownership should apply correctly.
     */
    @Test
    void shouldApplyMilestoneOwnershipCorrectly() {

        TradeContext context = new TradeContext(
                "T1",
                100.0,
                101.0,
                1,
                ExitModel.MODERATE,
                OwnershipMode.MILESTONE
        );

        ExitEngine engine = new ExitEngine(context);

        engine.onPriceUpdate(106.0, context);
        engine.onPriceUpdate(113.0, context);
        engine.onPriceUpdate(134.0, context);

        // +34% milestone → 70%
        // open profit = 33
        // 70% = 23.1
        // SL = 124.1

        assertEquals(124.1, context.getCurrentStopLoss(), 0.0001);
    }

    /**
     * Force exit must:
     * - Close trade
     * - Prevent further updates
     */
    @Test
    void shouldForceExitAndPreventFurtherProcessing() {

        TradeContext context = new TradeContext(
                "T1",
                100.0,
                101.0,
                1,
                ExitModel.MODERATE,
                OwnershipMode.CONTINUOUS
        );

        ExitEngine engine = new ExitEngine(context);

        engine.forceExit(context, 110.0);

        assertTrue(context.isClosed());
        assertEquals(110.0, context.getCurrentStopLoss(), 0.0001);

        engine.onPriceUpdate(130.0, context);

        // SL must remain unchanged
        assertEquals(110.0, context.getCurrentStopLoss(), 0.0001);
    }
}
