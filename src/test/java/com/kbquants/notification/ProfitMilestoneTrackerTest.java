package com.kbquants.notification;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfitMilestoneTrackerTest {

    @Test
    void shouldNotFireBelowFirstThreshold() {

        ProfitMilestoneTracker tracker = new ProfitMilestoneTracker(100.0);

        assertTrue(tracker.checkAndAdvance(100.2).isEmpty()); // +0.2%
    }

    @Test
    void shouldFireExactlyAtFirstThreshold() {

        ProfitMilestoneTracker tracker = new ProfitMilestoneTracker(100.0);

        OptionalDouble result = tracker.checkAndAdvance(100.5); // +0.5%

        assertTrue(result.isPresent());
        assertEquals(0.5, result.getAsDouble());
    }

    @Test
    void shouldFireEachThresholdExactlyOnceAsProfitRatchetsUp() {

        ProfitMilestoneTracker tracker = new ProfitMilestoneTracker(100.0);

        assertEquals(0.5, tracker.checkAndAdvance(100.5).getAsDouble());
        assertTrue(tracker.checkAndAdvance(100.5).isEmpty()); // same level again -> no re-fire

        assertEquals(1.0, tracker.checkAndAdvance(101.0).getAsDouble());
        assertEquals(2.0, tracker.checkAndAdvance(102.0).getAsDouble());
    }

    @Test
    void shouldCollapseMultipleSkippedThresholdsIntoSingleHighestAlert() {

        ProfitMilestoneTracker tracker = new ProfitMilestoneTracker(100.0);

        // Jumps straight past 0.5, 1.0, 2.0, 3.0 to +5% in one tick.
        OptionalDouble result = tracker.checkAndAdvance(105.0);

        assertTrue(result.isPresent());
        assertEquals(5.0, result.getAsDouble());

        // Thresholds below 5.0 must not fire again later.
        assertTrue(tracker.checkAndAdvance(105.0).isEmpty());
        assertEquals(8.0, tracker.checkAndAdvance(108.0).getAsDouble());
    }

    @Test
    void shouldNotFireWhenPriceIsBelowEntry() {

        ProfitMilestoneTracker tracker = new ProfitMilestoneTracker(100.0);

        assertTrue(tracker.checkAndAdvance(95.0).isEmpty());
    }

    @Test
    void shouldStopFiringAfterHighestThresholdReached() {

        double[] thresholds = {0.5, 1.0};
        ProfitMilestoneTracker tracker = new ProfitMilestoneTracker(100.0, thresholds);

        assertEquals(1.0, tracker.checkAndAdvance(102.0).getAsDouble());
        assertFalse(tracker.checkAndAdvance(110.0).isPresent());
    }

    @Test
    void shouldSupportCustomThresholdLadder() {

        ProfitMilestoneTracker tracker = new ProfitMilestoneTracker(200.0, new double[]{1.0, 10.0});

        assertTrue(tracker.checkAndAdvance(200.5).isEmpty()); // +0.25%, below first custom threshold
        assertEquals(1.0, tracker.checkAndAdvance(202.0).getAsDouble());
    }
}
