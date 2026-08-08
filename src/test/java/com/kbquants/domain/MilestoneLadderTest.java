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
                new double[]{1.0, 2.0, 3.0, 5.0, 8.0, 13.0, 21.0, 34.0, 55.0},
                ladder.allThresholdsPercent());
    }

    /**
     * Every threshold is net profit above the cost-inclusive breakeven, so
     * the ladder opens at 1% rather than 0.5%: below that, round-trip costs
     * are a meaningful share of the gain.
     */
    @Test
    void shouldOpenAtOnePercentNetOfCosts() {
        assertEquals(1.0, MilestoneLadder.defaultLadder().allThresholdsPercent()[0]);
    }

    @Test
    void shouldExposePhase2TriggerAtTwoPercent() {

        MilestoneLadder ladder = MilestoneLadder.defaultLadder();

        assertEquals(0.02, ladder.phaseTriggerFraction(Phase.PHASE_2), 0.0001);
    }

    @Test
    void shouldExposePhase3TriggerAtFivePercent() {

        MilestoneLadder ladder = MilestoneLadder.defaultLadder();

        assertEquals(0.05, ladder.phaseTriggerFraction(Phase.PHASE_3), 0.0001);
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

        assertEquals(6, locks.size());
        assertEquals(0.30, locks.get(0.05));
        assertEquals(0.50, locks.get(0.08));
        assertEquals(0.65, locks.get(0.13));
        assertEquals(0.75, locks.get(0.21));
        assertEquals(0.85, locks.get(0.34));
        assertEquals(0.90, locks.get(0.55));
    }

    /** Locking must begin exactly where PHASE_3 does, or the gap between
     *  them is a dead zone: in PHASE_3 with no applicable lock, the stop
     *  would sit at breakeven while profit ran. */
    @Test
    void firstOwnershipLockShouldCoincideWithPhase3() {
        for (MilestoneLadder ladder : MilestoneSets.available()) {
            double firstLock = ladder.ownershipLockMap().keySet().iterator().next();
            assertEquals(ladder.phaseTriggerFraction(Phase.PHASE_3), firstLock, 0.0001, ladder.getName());
        }
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
