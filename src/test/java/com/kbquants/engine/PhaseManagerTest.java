package com.kbquants.engine;


import com.kbquants.domain.ExitModel;
import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for PhaseManager.
 * <p>
 * Responsibility:
 * - Verify correct phase transitions
 * - Ensure no backward transitions occur
 * - Ensure transitions happen only at defined thresholds
 * <p>
 * Does NOT test StopLossEngine or ownership logic.
 */
class PhaseManagerTest {

    private PhaseManager phaseManager;
    private TradeContext context;

    @BeforeEach
    void setup() {
        phaseManager = new PhaseManager();

        context = new TradeContext(
                "T1",
                100.0,      // entry price
                101.0,      // base price
                1,
                ExitModel.MODERATE,
                OwnershipMode.CONTINUOUS
        );
    }

    /**
     * PHASE_1 → PHASE_2
     * Trigger: +6% from entry
     */
    @Test
    void shouldMoveFromPhase1ToPhase2WhenPriceReachesSixPercent() {

        phaseManager.evaluatePhaseTransition(106.0, context);

        assertEquals(Phase.PHASE_2, context.getCurrentPhase());
    }

    /**
     * Should remain in PHASE_1
     * when price is below +6%.
     */
    @Test
    void shouldRemainInPhase1IfBelowSixPercent() {

        phaseManager.evaluatePhaseTransition(105.9, context);

        assertEquals(Phase.PHASE_1, context.getCurrentPhase());
    }

    /**
     * PHASE_2 → PHASE_3
     * Trigger: +13% from entry
     */
    @Test
    void shouldMoveFromPhase2ToPhase3WhenPriceReachesThirteenPercent() {

        // First move to Phase 2
        phaseManager.evaluatePhaseTransition(106.0, context);
        assertEquals(Phase.PHASE_2, context.getCurrentPhase());

        // Now trigger Phase 3
        phaseManager.evaluatePhaseTransition(113.0, context);

        assertEquals(Phase.PHASE_3, context.getCurrentPhase());
    }

    /**
     * Once in PHASE_3,
     * system must not revert back to PHASE_2
     * even if price falls.
     */
    @Test
    void shouldNotMoveBackwardFromPhase3ToPhase2() {

        // Move to Phase 3
        phaseManager.evaluatePhaseTransition(106.0, context);
        phaseManager.evaluatePhaseTransition(113.0, context);

        assertEquals(Phase.PHASE_3, context.getCurrentPhase());

        // Price drops significantly
        phaseManager.evaluatePhaseTransition(90.0, context);

        assertEquals(Phase.PHASE_3, context.getCurrentPhase());
    }

    /**
     * Once in PHASE_2,
     * falling below +6% should NOT revert to PHASE_1.
     */
    @Test
    void shouldNotMoveBackwardFromPhase2ToPhase1() {

        phaseManager.evaluatePhaseTransition(106.0, context);
        assertEquals(Phase.PHASE_2, context.getCurrentPhase());

        // Drop below trigger
        phaseManager.evaluatePhaseTransition(95.0, context);

        assertEquals(Phase.PHASE_2, context.getCurrentPhase());
    }
}
