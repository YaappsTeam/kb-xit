package com.kbquants.engine;

import com.kbquants.domain.ExitModel;
import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhaseManagerTest {

    private PhaseManager phaseManager;
    private TradeContext context;

    @BeforeEach
    void setup() {
        phaseManager = new PhaseManager();

        context = new TradeContext(
                "T1",
                100.0,
                101.0,
                1,
                ExitModel.MODERATE,
                OwnershipMode.CONTINUOUS
        );
    }

    // base = 101, so PHASE_2 triggers at 101 x 1.02 = 103.02
    // and PHASE_3 at 101 x 1.05 = 106.05.

    @Test
    void shouldMoveFromPhase1ToPhase2AtTwoPercentAboveBase() {

        phaseManager.evaluatePhaseTransition(103.02, context);

        assertEquals(Phase.PHASE_2, context.getCurrentPhase());
    }

    @Test
    void shouldRemainInPhase1BelowTwoPercentAboveBase() {

        phaseManager.evaluatePhaseTransition(103.0, context);

        assertEquals(Phase.PHASE_1, context.getCurrentPhase());
    }

    @Test
    void shouldMoveFromPhase2ToPhase3AtFivePercentAboveBase() {

        phaseManager.evaluatePhaseTransition(103.02, context);
        assertEquals(Phase.PHASE_2, context.getCurrentPhase());

        phaseManager.evaluatePhaseTransition(106.10, context);

        assertEquals(Phase.PHASE_3, context.getCurrentPhase());
    }

    /**
     * A price that clears both thresholds applies both transitions in the
     * same tick, per PRODUCT_REQUIREMENTS F4.
     * <p>
     * Advancing one phase per tick left the trade in PHASE_2 until another
     * tick arrived, and since ownership locking is PHASE_3-only, the lock
     * was missed for exactly the tick where a violent move made it most
     * valuable.
     */
    @Test
    void shouldApplyEveryTransitionAPriceQualifiesForInOneTick() {

        // base = 101, so PHASE_2 needs 103.02 and PHASE_3 needs 106.05.
        phaseManager.evaluatePhaseTransition(140.0, context);

        assertEquals(Phase.PHASE_3, context.getCurrentPhase());
    }

    @Test
    void shouldStopAtTheHighestPhaseTheGapReaches() {

        // Clears PHASE_2 (103.02) but not PHASE_3 (106.05).
        phaseManager.evaluatePhaseTransition(105.0, context);

        assertEquals(Phase.PHASE_2, context.getCurrentPhase());
    }

    @Test
    void repeatedEvaluationAtTheSamePriceShouldBeStable() {

        phaseManager.evaluatePhaseTransition(140.0, context);
        phaseManager.evaluatePhaseTransition(140.0, context);
        phaseManager.evaluatePhaseTransition(140.0, context);

        assertEquals(Phase.PHASE_3, context.getCurrentPhase());
    }

    /**
     * Thresholds measure from basePrice, not entryPrice. With costs heavy
     * enough to push breakeven to 110, a price of 105 is still a net loss
     * and must not advance the phase -- under entry-anchored thresholds it
     * would have counted as +5%.
     */
    @Test
    void shouldMeasureFromBreakevenNotEntry() {

        TradeContext costly = new TradeContext(
                "T2", 100.0, 110.0, 1, ExitModel.MODERATE, OwnershipMode.CONTINUOUS);

        phaseManager.evaluatePhaseTransition(105.0, costly);

        assertEquals(Phase.PHASE_1, costly.getCurrentPhase());
    }

    @Test
    void shouldNotMoveBackwardFromPhase3ToPhase2() {

        phaseManager.evaluatePhaseTransition(105.0, context);
        phaseManager.evaluatePhaseTransition(113.0, context);

        assertEquals(Phase.PHASE_3, context.getCurrentPhase());

        phaseManager.evaluatePhaseTransition(90.0, context);

        assertEquals(Phase.PHASE_3, context.getCurrentPhase());
    }

    @Test
    void shouldNotMoveBackwardFromPhase2ToPhase1() {

        phaseManager.evaluatePhaseTransition(105.0, context);
        assertEquals(Phase.PHASE_2, context.getCurrentPhase());

        phaseManager.evaluatePhaseTransition(95.0, context);

        assertEquals(Phase.PHASE_2, context.getCurrentPhase());
    }
}
