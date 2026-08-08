package com.kbquants.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the milestone set registry and the option ladder's shape.
 */
class MilestoneSetsTest {

    @Test
    void shouldOfferEquityAndOptionSets() {
        assertEquals(java.util.List.of("EQUITY", "OPTIONS"), MilestoneSets.names());
    }

    @Test
    void shouldLookUpByNameCaseInsensitively() {
        assertTrue(MilestoneSets.byName("options").isPresent());
        assertTrue(MilestoneSets.byName("  Options  ").isPresent());
    }

    @Test
    void shouldReturnEmptyForUnknownSet() {
        assertTrue(MilestoneSets.byName("NOSUCHSET").isEmpty());
        assertTrue(MilestoneSets.byName(null).isEmpty());
    }

    @Test
    void descriptionShouldCarryRangeAndHardStop() {

        String description = MilestoneSets.describe(MilestoneLadder.optionsLadder());

        assertTrue(description.contains("OPTIONS"));
        assertTrue(description.contains("1%"));
        assertTrue(description.contains("233%"));
        assertTrue(description.contains("40%"));
    }

    /** Fractional thresholds must not be rounded away to whole numbers. */
    @Test
    void descriptionShouldKeepFractionalThresholds() {

        assertEquals("55", MilestoneSets.trim(55.0));
        assertEquals("1.3", MilestoneSets.trim(1.3));
        assertEquals("0.5", MilestoneSets.trim(0.5));
    }

    /**
     * Both ladders open at 1%: the user's stated minimum target, measured
     * net of round-trip costs rather than gross.
     */
    @Test
    void bothLaddersShouldOpenAtOnePercentNet() {
        for (MilestoneLadder ladder : MilestoneSets.available()) {
            assertEquals(1.0, ladder.allThresholdsPercent()[0], ladder.getName());
        }
    }

    /**
     * The opening rung notifies only -- it must not drag a phase
     * transition or an ownership lock down with it.
     */
    @Test
    void openingRungShouldCarryNoPhaseOrLock() {
        for (MilestoneLadder ladder : MilestoneSets.available()) {
            Milestone first = ladder.getMilestones().get(0);
            assertFalse(first.hasPhaseTransition(), ladder.getName());
            assertFalse(first.hasOwnershipLock(), ladder.getName());
        }
    }

    /**
     * The reason the option set exists: a 20% stop is a routine wiggle on
     * an option premium and would stop out nearly every trade.
     */
    @Test
    void optionLadderShouldUseAWiderHardStopThanEquities() {
        assertTrue(MilestoneLadder.optionsLadder().getHardStopPercent()
                > MilestoneLadder.equityLadder().getHardStopPercent());
    }

    /**
     * Options need more headroom than equities: a premium swings further,
     * so both phases and the hard stop sit higher.
     */
    @Test
    void optionLadderShouldPlacePhasesHigherThanEquities() {

        MilestoneLadder equity = MilestoneLadder.equityLadder();
        MilestoneLadder options = MilestoneLadder.optionsLadder();

        assertTrue(options.phaseTriggerFraction(Phase.PHASE_2) > equity.phaseTriggerFraction(Phase.PHASE_2));
        assertTrue(options.phaseTriggerFraction(Phase.PHASE_3) > equity.phaseTriggerFraction(Phase.PHASE_3));
        assertTrue(options.allThresholdsPercent()[options.allThresholdsPercent().length - 1]
                > equity.allThresholdsPercent()[equity.allThresholdsPercent().length - 1]);
    }

    @Test
    void bothLaddersShouldDefinePhaseTriggers() {
        for (MilestoneLadder ladder : MilestoneSets.available()) {
            assertTrue(ladder.phaseTriggerFraction(Phase.PHASE_2) > 0, ladder.getName());
            assertTrue(ladder.phaseTriggerFraction(Phase.PHASE_3) > 0, ladder.getName());
        }
    }

    @Test
    void thresholdsShouldBeStrictlyAscending() {
        for (MilestoneLadder ladder : MilestoneSets.available()) {
            double[] thresholds = ladder.allThresholdsPercent();
            for (int i = 1; i < thresholds.length; i++) {
                assertTrue(thresholds[i] > thresholds[i - 1], ladder.getName() + " at rung " + i);
            }
        }
    }

    @Test
    void ownershipLocksShouldBeAscendingAndBounded() {
        for (MilestoneLadder ladder : MilestoneSets.available()) {
            double previous = 0;
            for (double lock : ladder.ownershipLockMap().values()) {
                assertTrue(lock > previous, ladder.getName());
                assertTrue(lock <= 1.0, ladder.getName());
                previous = lock;
            }
        }
    }

    @Test
    void defaultLadderShouldStillBeTheEquitySet() {
        assertEquals("EQUITY", MilestoneLadder.defaultLadder().getName());
    }

    @Test
    void phaseTwoShouldComeBeforePhaseThreeInBothSets() {
        for (MilestoneLadder ladder : MilestoneSets.available()) {
            assertFalse(ladder.phaseTriggerFraction(Phase.PHASE_2) >= ladder.phaseTriggerFraction(Phase.PHASE_3),
                    ladder.getName());
        }
    }
}
