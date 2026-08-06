package com.kbquants.notification;

import com.kbquants.domain.MilestoneLadder;

import java.util.Objects;
import java.util.OptionalDouble;

public class ProfitMilestoneTracker {

    private final double entryPrice;
    private final double[] thresholdsPercent;
    private int nextThresholdIndex = 0;

    public ProfitMilestoneTracker(double entryPrice) {
        this(entryPrice, MilestoneLadder.defaultLadder());
    }

    public ProfitMilestoneTracker(double entryPrice, MilestoneLadder ladder) {
        this(entryPrice, ladder.allThresholdsPercent());
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
