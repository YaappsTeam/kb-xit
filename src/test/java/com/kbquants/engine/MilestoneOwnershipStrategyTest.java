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

    // base = 101 (cost-inclusive breakeven), and both the milestone
    // thresholds and the open profit are measured from it.

    /**
     * Should not apply ownership below the first lock rung (+5% of base).
     */
    @Test
    void shouldNotApplyOwnershipBelowFirstMilestone() {

        strategy.apply(105.0, context); // (105-101)/101 = 3.96%, below 5%

        assertEquals(0.0, context.getCurrentStopLoss());
    }

    /**
     * Should apply 30% ownership at the +5% rung.
     */
    @Test
    void shouldApplyFirstMilestoneOwnership() {

        strategy.apply(106.10, context); // +5.05% of base, clears the +5% rung

        // open profit = 106.10 - 101 = 5.10
        // 30% = 1.53
        // SL = 102.53

        assertEquals(102.53, context.getCurrentStopLoss(), 0.0001);
    }

    /**
     * Should apply the higher lock once the +8% rung is crossed.
     */
    @Test
    void shouldApplySecondMilestoneOwnership() {

        strategy.apply(109.15, context); // +8.07% of base

        // open profit = 8.15, 50% = 4.075
        // SL = 105.075

        assertEquals(105.075, context.getCurrentStopLoss(), 0.0001);
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
