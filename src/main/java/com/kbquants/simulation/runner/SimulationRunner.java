package com.kbquants.simulation.runner;


import com.kbquants.simulation.MarketRegime;
import com.kbquants.simulation.PricePathGenerator;
import com.kbquants.simulation.ScenarioConfig;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Top-level research orchestrator for simulation execution.
 * <p>
 * Responsibilities:
 * - Generate price path using configured generator.
 * - Select execution strategy (Sequential or Parallel).
 * - Execute all combinations.
 * - Log structured execution summary.
 * <p>
 * This class:
 * - Does NOT contain trading logic.
 * - Does NOT modify engine behavior.
 * - Does NOT contain broker logic.
 * <p>
 * Layer: simulation.runner
 */
@Slf4j
public class SimulationRunner {

    private static final double DEFAULT_START_PRICE = 100.0;
    private final PricePathGenerator pricePathGenerator;

    public SimulationRunner(PricePathGenerator pricePathGenerator) {
        this.pricePathGenerator = Objects.requireNonNull(pricePathGenerator, "pricePathGenerator must not be null");
    }

    /**
     * Executes a full simulation run.
     */
    public SimulationResult run(SimulationRequest request) {

        Objects.requireNonNull(request, "request must not be null");

        Instant start = Instant.now();

        log.info("Simulation started");
        log.info("Regime={}, ExecutionMode={}", request.getMarketRegime(), request.getExecutionMode());

        ScenarioConfig config = request.getScenarioConfig();
        MarketRegime regime = request.getMarketRegime();

        List<Double> pricePath = pricePathGenerator.generate(DEFAULT_START_PRICE, config, regime);
        if (pricePath.isEmpty()) {
            throw new IllegalArgumentException("Generated price path must not be empty");
        }
        log.info("Generated price path length={}", pricePath.size());

        CombinationExecutor executor = resolveExecutor(request.getExecutionMode());
        SimulationResult result = executor.execute(pricePath, request);
        Duration duration = Duration.between(start, Instant.now());
        log.info("Simulation completed in {} ms", duration.toMillis());

        return result;
    }

    private CombinationExecutor resolveExecutor(ExecutionMode mode) {
        if (mode == ExecutionMode.PARALLEL) {
            throw new UnsupportedOperationException("Parallel execution not implemented yet");
        }
        return new SequentialCombinationExecutor();
    }
}
