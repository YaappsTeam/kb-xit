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

    @Test
    void shouldMoveFromPhase1ToPhase2WhenPriceReachesFivePercent() {

        phaseManager.evaluatePhaseTransition(105.0, context);

        assertEquals(Phase.PHASE_2, context.getCurrentPhase());
    }

    @Test
    void shouldRemainInPhase1IfBelowFivePercent() {

        phaseManager.evaluatePhaseTransition(104.9, context);

        assertEquals(Phase.PHASE_1, context.getCurrentPhase());
    }

    @Test
    void shouldMoveFromPhase2ToPhase3WhenPriceReachesThirteenPercent() {

        phaseManager.evaluatePhaseTransition(105.0, context);
        assertEquals(Phase.PHASE_2, context.getCurrentPhase());

        phaseManager.evaluatePhaseTransition(113.0, context);

        assertEquals(Phase.PHASE_3, context.getCurrentPhase());
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
