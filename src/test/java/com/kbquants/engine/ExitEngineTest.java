package com.kbquants.engine;

import com.kbquants.domain.ExitModel;
import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExitEngineTest {

    @Test
    void shouldApplyHardSafetyBeforePhaseLogic() {

        TradeContext context = new TradeContext("T1", 100.0, 101.0, 1,
                ExitModel.MODERATE, OwnershipMode.CONTINUOUS);

        ExitEngine engine = new ExitEngine(context);

        engine.onPriceUpdate(95.0, context);

        assertEquals(80.0, context.getCurrentStopLoss(), 0.0001);
    }

    @Test
    void shouldMoveThroughAllPhasesCorrectly() {

        TradeContext context = new TradeContext("T1", 100.0, 101.0, 1,
                ExitModel.MODERATE, OwnershipMode.CONTINUOUS);

        ExitEngine engine = new ExitEngine(context);
        assertEquals(Phase.PHASE_1, context.getCurrentPhase());

        engine.onPriceUpdate(105.0, context);
        assertEquals(Phase.PHASE_2, context.getCurrentPhase());

        engine.onPriceUpdate(113.0, context);
        assertEquals(Phase.PHASE_3, context.getCurrentPhase());
    }

    @Test
    void shouldApplyBaseProtectionInPhase2() {

        TradeContext context = new TradeContext("T1", 100.0, 101.0, 1,
                ExitModel.MODERATE, OwnershipMode.CONTINUOUS);

        ExitEngine engine = new ExitEngine(context);

        engine.onPriceUpdate(105.0, context);

        assertEquals(101.0, context.getCurrentStopLoss());
    }

    @Test
    void shouldApplyContinuousOwnershipCorrectly() {

        TradeContext context = new TradeContext("T1", 100.0, 101.0, 1,
                ExitModel.MODERATE, OwnershipMode.CONTINUOUS);

        ExitEngine engine = new ExitEngine(context);

        engine.onPriceUpdate(105.0, context);
        engine.onPriceUpdate(113.0, context);
        engine.onPriceUpdate(120.0, context);

        // base=101, openProfit=19, 30%=5.7, SL=106.7
        assertEquals(106.7, context.getCurrentStopLoss(), 0.0001);
    }

    @Test
    void shouldApplyMilestoneOwnershipCorrectly() {

        TradeContext context = new TradeContext(
                "T1", 100.0, 101.0, 1,
                ExitModel.MODERATE, OwnershipMode.MILESTONE);

        ExitEngine engine = new ExitEngine(context);

        engine.onPriceUpdate(105.0, context);
        engine.onPriceUpdate(113.0, context);
        engine.onPriceUpdate(134.0, context);

        // net profit from base = (134-101)/101 = 32.67%, so the +21% rung
        // applies -> 75%. openProfit = 33, 75% = 24.75, SL = 125.75
        assertEquals(125.75, context.getCurrentStopLoss(), 0.0001);
    }

    @Test
    void shouldForceExitAndPreventFurtherProcessing() {

        TradeContext context = new TradeContext(
                "T1", 100.0, 101.0, 1,
                ExitModel.MODERATE, OwnershipMode.CONTINUOUS);

        ExitEngine engine = new ExitEngine(context);

        engine.forceExit(context, 110.0);

        assertTrue(context.isClosed());
        assertEquals(110.0, context.getCurrentStopLoss(), 0.0001);

        engine.onPriceUpdate(130.0, context);

        assertEquals(110.0, context.getCurrentStopLoss(), 0.0001);
    }
}
