package com.kbquants.notification;

import com.kbquants.domain.MilestoneLadder;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Fires each profit milestone exactly once, ascending.
 * <p>
 * Thresholds are measured from basePrice -- the cost-inclusive breakeven --
 * so a reported "+1%" is 1% of money kept after brokerage and taxes, not a
 * gross figure that still has costs to come out of it. On a small position
 * round-trip costs can exceed 1.5% of capital, which is enough for a gross
 * gain to be a net loss; reporting from entry price would announce a profit
 * on a losing trade.
 */
public class ProfitMilestoneTracker {

    private final double basePrice;
    private final double[] thresholdsPercent;
    private int nextThresholdIndex = 0;

    public ProfitMilestoneTracker(double basePrice) {
        this(basePrice, MilestoneLadder.defaultLadder());
    }

    public ProfitMilestoneTracker(double basePrice, MilestoneLadder ladder) {
        this(basePrice, ladder.allThresholdsPercent());
    }

    public ProfitMilestoneTracker(double basePrice, double[] thresholdsPercent) {
        this.basePrice = basePrice;
        this.thresholdsPercent = Objects.requireNonNull(thresholdsPercent, "thresholdsPercent must not be null").clone();
    }

    /**
     * Resumes a tracker mid-ladder after a restart. Without this, every
     * milestone the trade had already passed would fire again on the first
     * tick back -- a burst of notifications announcing profit that was
     * reported hours ago.
     */
    public ProfitMilestoneTracker(double basePrice, MilestoneLadder ladder, int nextThresholdIndex) {
        this(basePrice, ladder);
        if (nextThresholdIndex < 0 || nextThresholdIndex > thresholdsPercent.length) {
            throw new IllegalArgumentException("nextThresholdIndex out of range: " + nextThresholdIndex);
        }
        this.nextThresholdIndex = nextThresholdIndex;
    }

    /** How far up the ladder this trade has already been reported. */
    public int getNextThresholdIndex() {
        return nextThresholdIndex;
    }

    public OptionalDouble checkAndAdvance(double currentPrice) {

        double profitPercent = (currentPrice - basePrice) / basePrice * 100.0;

        double highestNewlyCrossed = Double.NaN;

        while (nextThresholdIndex < thresholdsPercent.length
                && profitPercent >= thresholdsPercent[nextThresholdIndex]) {
            highestNewlyCrossed = thresholdsPercent[nextThresholdIndex];
            nextThresholdIndex++;
        }

        return Double.isNaN(highestNewlyCrossed) ? OptionalDouble.empty() : OptionalDouble.of(highestNewlyCrossed);
    }
}
