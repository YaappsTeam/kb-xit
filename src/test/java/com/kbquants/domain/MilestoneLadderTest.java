package com.kbquants.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MilestoneLadderTest {

    @Test
    void shouldExposeAllThresholdsInAscendingOrder() {

        MilestoneLadder ladder = MilestoneLadder.defaultLadder();

        assertArrayEquals(
                new double[]{0.5, 1.0, 2.0, 3.0, 5.0, 8.0, 13.0, 21.0, 34.0, 55.0},
                ladder.allThresholdsPercent());
    }

    @Test
    void shouldExposePhase2TriggerAtFivePercent() {

        MilestoneLadder ladder = MilestoneLadder.defaultLadder();

        assertEquals(0.05, ladder.phaseTriggerFraction(Phase.PHASE_2), 0.0001);
    }

    @Test
    void shouldExposePhase3TriggerAtThirteenPercent() {

        MilestoneLadder ladder = MilestoneLadder.defaultLadder();

        assertEquals(0.13, ladder.phaseTriggerFraction(Phase.PHASE_3), 0.0001);
    }

    @Test
    void shouldThrowWhenPhaseHasNoTrigger() {

        MilestoneLadder ladder = MilestoneLadder.defaultLadder();

        assertThrows(IllegalArgumentException.class, () -> ladder.phaseTriggerFraction(Phase.PHASE_1));
    }

    @Test
    void shouldExposeOwnershipLockMapAsFractionsInAscendingOrder() {

        MilestoneLadder ladder = MilestoneLadder.defaultLadder();

        Map<Double, Double> locks = ladder.ownershipLockMap();

        assertEquals(4, locks.size());
        assertEquals(0.30, locks.get(0.13));
        assertEquals(0.50, locks.get(0.21));
        assertEquals(0.70, locks.get(0.34));
        assertEquals(0.85, locks.get(0.55));
    }

    @Test
    void shouldSupportCustomLadder() {

        MilestoneLadder ladder = new MilestoneLadder(List.of(
                new Milestone(2.0, Phase.PHASE_2, 0.0),
                new Milestone(10.0, Phase.PHASE_3, 0.40)
        ));

        assertArrayEquals(new double[]{2.0, 10.0}, ladder.allThresholdsPercent());
        assertEquals(0.02, ladder.phaseTriggerFraction(Phase.PHASE_2), 0.0001);
        assertEquals(0.40, ladder.ownershipLockMap().get(0.10));
    }

    @Test
    void shouldRejectEmptyLadder() {

        assertThrows(IllegalArgumentException.class, () -> new MilestoneLadder(List.of()));
    }

    @Test
    void shouldRejectNonPositiveMilestonePercent() {

        assertThrows(IllegalArgumentException.class, () -> new Milestone(0.0, null, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new Milestone(-1.0, null, 0.0));
    }

    @Test
    void shouldRejectOwnershipLockOutsideValidRange() {

        assertThrows(IllegalArgumentException.class, () -> new Milestone(5.0, null, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new Milestone(5.0, null, 1.1));
    }
}
