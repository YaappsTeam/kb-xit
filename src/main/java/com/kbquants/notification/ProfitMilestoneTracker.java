package com.kbquants.notification;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Tracks profit-from-entry milestones for a single live trade and reports
 * each one exactly once, in ascending order, as the live price ratchets
 * through it -- mirroring how PhaseManager ratchets through its own
 * (unrelated) thresholds. This is purely a notification concern: it does
 * not read or influence TradeContext/ExitEngine in any way.
 * <p>
 * If a price update jumps past more than one un-reached threshold at once,
 * all of them are consumed but only the highest is reported -- one message
 * per update, not one per skipped milestone.
 */
public class ProfitMilestoneTracker {

    public static final double[] DEFAULT_THRESHOLDS_PERCENT = {0.5, 1.0, 2.0, 3.0, 5.0, 8.0, 13.0};

    private final double entryPrice;
    private final double[] thresholdsPercent;
    private int nextThresholdIndex = 0;

    public ProfitMilestoneTracker(double entryPrice) {
        this(entryPrice, DEFAULT_THRESHOLDS_PERCENT);
    }

    public ProfitMilestoneTracker(double entryPrice, double[] thresholdsPercent) {
        this.entryPrice = entryPrice;
        this.thresholdsPercent = Objects.requireNonNull(thresholdsPercent, "thresholdsPercent must not be null").clone();
    }

    public OptionalDouble checkAndAdvance(double currentPrice) {

        double profitPercent = (currentPrice - entryPrice) / entryPrice * 100.0;

        double highestNewlyCrossed = Double.NaN;

        while (nextThresholdIndex < thresholdsPercent.length
                && profitPercent >= thresholdsPercent[nextThresholdIndex]) {
            highestNewlyCrossed = thresholdsPercent[nextThresholdIndex];
            nextThresholdIndex++;
        }

        return Double.isNaN(highestNewlyCrossed) ? OptionalDouble.empty() : OptionalDouble.of(highestNewlyCrossed);
    }
}
