package com.kbquants.notification;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The price passed in is the <b>basePrice</b> -- the cost-inclusive
 * breakeven -- not the entry price, so every threshold here is net profit.
 */
class ProfitMilestoneTrackerTest {

    @Test
    void shouldNotFireBelowFirstThreshold() {

        ProfitMilestoneTracker tracker = new ProfitMilestoneTracker(100.0);

        assertTrue(tracker.checkAndAdvance(100.5).isEmpty()); // +0.5%, below the 1% opening rung
    }

    @Test
    void shouldFireExactlyAtFirstThreshold() {

        ProfitMilestoneTracker tracker = new ProfitMilestoneTracker(100.0);

        OptionalDouble result = tracker.checkAndAdvance(101.0); // +1%

        assertTrue(result.isPresent());
        assertEquals(1.0, result.getAsDouble());
    }

    /**
     * Measured from breakeven, not entry: with costs pushing breakeven to
     * 101.55, a price of 101 is still a net loss and must fire nothing --
     * this is the case that would otherwise announce a profit on a losing
     * trade.
     */
    @Test
    void shouldNotFireOnAGrossGainThatIsANetLoss() {

        ProfitMilestoneTracker tracker = new ProfitMilestoneTracker(101.55);

        assertTrue(tracker.checkAndAdvance(101.0).isEmpty());
    }

    @Test
    void shouldFireEachThresholdExactlyOnceAsProfitRatchetsUp() {

        ProfitMilestoneTracker tracker = new ProfitMilestoneTracker(100.0);

        assertEquals(1.0, tracker.checkAndAdvance(101.0).getAsDouble());
        assertTrue(tracker.checkAndAdvance(101.0).isEmpty()); // same level again -> no re-fire

        assertEquals(2.0, tracker.checkAndAdvance(102.0).getAsDouble());
        assertEquals(3.0, tracker.checkAndAdvance(103.0).getAsDouble());
    }

    @Test
    void shouldCollapseMultipleSkippedThresholdsIntoSingleHighestAlert() {

        ProfitMilestoneTracker tracker = new ProfitMilestoneTracker(100.0);

        // Jumps straight past 1.0, 2.0, 3.0 to +5% in one tick.
        OptionalDouble result = tracker.checkAndAdvance(105.0);

        assertTrue(result.isPresent());
        assertEquals(5.0, result.getAsDouble());

        // Thresholds below 5.0 must not fire again later.
        assertTrue(tracker.checkAndAdvance(105.0).isEmpty());
        assertEquals(8.0, tracker.checkAndAdvance(108.0).getAsDouble());
    }

    @Test
    void shouldNotFireWhenPriceIsBelowBreakeven() {

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
