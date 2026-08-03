package com.kbquants.engine;


import com.kbquants.domain.ExitModel;
import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for MilestoneOwnershipStrategy.
 * <p>
 * Responsibility:
 * - Apply ownership only at defined milestones
 * - Use the highest milestone crossed
 * - Respect monotonic SL invariant
 * <p>
 * Does NOT test phase transitions or hard safety.
 */
class MilestoneOwnershipStrategyTest {

    private MilestoneOwnershipStrategy strategy;
    private TradeContext context;

    @BeforeEach
    void setup() {

        StopLossEngine stopLossEngine = new StopLossEngine();
        strategy = new MilestoneOwnershipStrategy(stopLossEngine);

        context = new TradeContext(
                "T1",
                100.0,     // entry
                101.0,     // base
                1,
                ExitModel.MODERATE,
                OwnershipMode.MILESTONE
        );

        context.setCurrentPhase(Phase.PHASE_3);
    }

    /**
     * Should not apply ownership below first milestone (+13%).
     */
    @Test
    void shouldNotApplyOwnershipBelowFirstMilestone() {

        strategy.apply(112.0, context); // +12%

        assertEquals(0.0, context.getCurrentStopLoss());
    }

    /**
     * Should apply 30% ownership at +13% milestone.
     */
    @Test
    void shouldApplyFirstMilestoneOwnership() {

        strategy.apply(113.0, context);

        // base = 101
        // open profit = 12
        // 30% = 3.6
        // SL = 104.6

        assertEquals(104.6, context.getCurrentStopLoss(), 0.0001);
    }

    /**
     * Should apply correct milestone when price crosses +21%.
     */
    @Test
    void shouldApplySecondMilestoneOwnership() {

        strategy.apply(121.0, context); // +21%

        // open profit = 20
        // 50% = 10
        // SL = 111

        assertEquals(111.0, context.getCurrentStopLoss(), 0.0001);
    }

    /**
     * If price jumps across multiple milestones,
     * highest applicable milestone must be used.
     */
    @Test
    void shouldApplyHighestMilestoneWhenJumpingMultipleLevels() {

        strategy.apply(155.0, context); // +55%

        // open profit = 54
        // 85% = 45.9
        // SL = 146.9

        assertEquals(146.9, context.getCurrentStopLoss(), 0.0001);
    }

    /**
     * Should respect monotonic SL rule.
     * SL must not decrease if price retraces.
     */
    @Test
    void shouldRespectMonotonicStopLossRule() {

        strategy.apply(155.0, context);
        double higherSl = context.getCurrentStopLoss();

        // price retraces but still above milestone
        strategy.apply(130.0, context);

        assertEquals(higherSl, context.getCurrentStopLoss());
    }

    /**
     * Ownership must not apply if not in PHASE_3.
     */
    @Test
    void shouldNotApplyOwnershipOutsidePhase3() {

        context.setCurrentPhase(Phase.PHASE_2);

        strategy.apply(155.0, context);

        assertEquals(0.0, context.getCurrentStopLoss());
    }
}
