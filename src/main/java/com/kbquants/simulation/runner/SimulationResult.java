package com.kbquants.simulation.runner;


import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable container holding results of a simulation run.
 * <p>
 * Responsibilities:
 * - Store metrics for all evaluated combinations.
 * - Prevent mutation of collected results.
 * <p>
 * This class performs no aggregation logic.
 * Any ranking or comparison must be handled externally.
 * <p>
 * Layer: simulation.runner
 */
@Getter
public final class SimulationResult {

    private final List<TradeMetrics> tradeMetricsList;

    public SimulationResult(List<TradeMetrics> tradeMetricsList) {
        Objects.requireNonNull(tradeMetricsList, "tradeMetricsList must not be null");
        this.tradeMetricsList = Collections.unmodifiableList(tradeMetricsList);
    }
}
