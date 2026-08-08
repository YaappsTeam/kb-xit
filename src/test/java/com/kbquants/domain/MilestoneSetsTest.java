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
        assertTrue(description.contains("1.3%"));
        assertTrue(description.contains("233%"));
        assertTrue(description.contains("40%"));
    }

    /** Fractional rungs must not be rounded away: 0.5% is not "0%". */
    @Test
    void descriptionShouldKeepFractionalThresholds() {

        assertTrue(MilestoneSets.describe(MilestoneLadder.equityLadder()).contains("0.5%"));

        assertEquals("55", MilestoneSets.trim(55.0));
        assertEquals("1.3", MilestoneSets.trim(1.3));
        assertEquals("0.5", MilestoneSets.trim(0.5));
    }

    @Test
    void optionLadderShouldOpenAtTheEarlySignalRung() {

        double[] thresholds = MilestoneLadder.optionsLadder().allThresholdsPercent();

        assertEquals(1.3, thresholds[0]);
    }

    /**
     * The opening rung notifies only -- it must not drag a phase
     * transition or an ownership lock down with it.
     */
    @Test
    void optionLadderOpeningRungShouldCarryNoPhaseOrLock() {

        Milestone first = MilestoneLadder.optionsLadder().getMilestones().get(0);

        assertFalse(first.hasPhaseTransition());
        assertFalse(first.hasOwnershipLock());
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

    @Test
    void optionLadderShouldStartWhereTheEquityLadderEnds() {

        double[] equity = MilestoneLadder.equityLadder().allThresholdsPercent();
        double[] options = MilestoneLadder.optionsLadder().allThresholdsPercent();

        assertEquals(equity[equity.length - 1], options[options.length - 4]);
        assertTrue(options[0] > equity[0]);
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
