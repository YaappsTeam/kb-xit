package com.kbquants.simulation.runner;


import com.kbquants.domain.TradeContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Collects runtime metrics during a single trade replay.
 * <p>
 * Responsibilities:
 * - Track MFE and MAE relative to entry price.
 * - Observe ownership activation.
 * - Observe force exit.
 * - Observe hybrid update count.
 * - Produce immutable TradeMetrics at end.
 * <p>
 * This class:
 * - Does NOT modify engine behavior.
 * - Does NOT contain trading logic.
 * - Only observes state from TradeContext.
 * <p>
 * Layer: simulation.runner
 */
@Slf4j
public class MetricsCollector {

    private final double entryPrice;

    private double maxFavorableExcursion = Double.NEGATIVE_INFINITY;
    private double maxAdverseExcursion = Double.POSITIVE_INFINITY;

    private boolean forceExited = false;

    public MetricsCollector(double entryPrice) {
        this.entryPrice = entryPrice;
    }

    /**
     * Called on every price update.
     */
    public void onPrice(double currentPrice, TradeContext context) {

        double move = currentPrice - entryPrice;

        maxFavorableExcursion = Math.max(maxFavorableExcursion, move);
        maxAdverseExcursion = Math.min(maxAdverseExcursion, move);

    }

    /**
     * Called once at end of replay.
     */
    public void onCompletion(TradeContext context) {
        this.forceExited = context.isForceExited();
    }

    public TradeMetrics build(
            String tradeId,
            TradeContext context
    ) {
        return new TradeMetrics(
                context.getOwnershipMode(),
                context.getCurrentStopLoss(),
                context.getCurrentPhase(),
                maxFavorableExcursion,
                maxAdverseExcursion,
                forceExited,
                context.isClosed()
        );
    }
}
