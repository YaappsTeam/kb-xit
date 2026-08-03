package com.kbquants.engine;


import com.kbquants.domain.ExitModel;
import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for ContinuousOwnershipStrategy.
 * <p>
 * Responsibility:
 * - Lock fixed percentage of open profit
 * - Only active in PHASE_3
 * - Must respect monotonic SL rule
 * <p>
 * Does NOT test phase transitions or hard safety.
 */
class ContinuousOwnershipStrategyTest {

    private ContinuousOwnershipStrategy strategy;
    private TradeContext context;

    @BeforeEach
    void setup() {

        StopLossEngine stopLossEngine = new StopLossEngine();
        strategy = new ContinuousOwnershipStrategy(stopLossEngine);

        context = new TradeContext(
                "T1",
                100.0,     // entry
                101.0,     // base
                1,
                ExitModel.MODERATE,
                OwnershipMode.CONTINUOUS
        );
    }

    /**
     * Ownership must not apply before PHASE_3.
     */
    @Test
    void shouldNotApplyOwnershipBeforePhase3() {

        context.setCurrentPhase(Phase.PHASE_2);

        strategy.apply(120.0, context);

        assertEquals(0.0, context.getCurrentStopLoss());
    }

    /**
     * Should lock 30% of open profit in PHASE_3.
     */
    @Test
    void shouldLockThirtyPercentOfOpenProfitInPhase3() {

        context.setCurrentPhase(Phase.PHASE_3);

        // current price = 120
        // base = 101
        // open profit = 19
        // 30% = 5.7
        // SL = 106.7

        strategy.apply(120.0, context);

        assertEquals(106.7, context.getCurrentStopLoss(), 0.0001);
    }

    /**
     * Should not apply ownership if open profit is zero or negative.
     */
    @Test
    void shouldNotApplyOwnershipIfNoOpenProfit() {

        context.setCurrentPhase(Phase.PHASE_3);

        // current price equal to base
        strategy.apply(101.0, context);

        assertEquals(0.0, context.getCurrentStopLoss());
    }

    /**
     * Should update SL upward when price increases further.
     */
    @Test
    void shouldIncreaseStopLossWhenProfitIncreases() {

        context.setCurrentPhase(Phase.PHASE_3);

        strategy.apply(120.0, context);
        double firstSl = context.getCurrentStopLoss();

        strategy.apply(130.0, context);
        double secondSl = context.getCurrentStopLoss();

        assertTrue(secondSl > firstSl);
    }

    /**
     * Should respect monotonic rule.
     * If candidate SL is lower than current, it must not reduce SL.
     */
    @Test
    void shouldRespectMonotonicStopLossRule() {

        context.setCurrentPhase(Phase.PHASE_3);

        strategy.apply(130.0, context);
        double higherSl = context.getCurrentStopLoss();

        // Price drops but still PHASE_3
        strategy.apply(110.0, context);

        assertEquals(higherSl, context.getCurrentStopLoss());
    }
}
