package com.kbquants.simulation.runner;



import com.kbquants.domain.OwnershipMode;
import com.kbquants.simulation.MarketRegime;
import com.kbquants.simulation.ScenarioConfig;
import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable configuration object representing a single simulation run request.
 * <p>
 * Responsibilities:
 * - Encapsulates all input parameters required to execute a research simulation.
 * - Ensures structural validation before execution begins.
 * - Prevents mutation of exit model and ownership mode collections.
 * <p>
 * This class:
 * - Contains no business logic.
 * - Does not generate prices.
 * - Does not execute the engine.
 * - Is safe for future serialization.
 * <p>
 * Layer: simulation.runner
 */
@Getter
public final class SimulationRequest {

    private final MarketRegime marketRegime;
    private final ScenarioConfig scenarioConfig;
    private final List<OwnershipMode> ownershipModes;
    private final ExecutionMode executionMode;

    /**
     * Creates an immutable simulation request.
     *
     * @param marketRegime   the market regime under which prices will be generated
     * @param scenarioConfig scenario configuration including seed and drift parameters
     * @param ownershipModes list of ownership strategies to evaluate (must not be empty)
     * @param executionMode  execution strategy (sequential or parallel)
     * @throws NullPointerException     if any required argument is null
     * @throws IllegalArgumentException if ownershipModes is empty
     */
    public SimulationRequest(MarketRegime marketRegime, ScenarioConfig scenarioConfig,
                             List<OwnershipMode> ownershipModes, ExecutionMode executionMode) {

        this.marketRegime = Objects.requireNonNull(marketRegime, "marketRegime must not be null");
        this.scenarioConfig = Objects.requireNonNull(scenarioConfig, "scenarioConfig must not be null");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode must not be null");

        Objects.requireNonNull(ownershipModes, "ownershipModes must not be null");

        if (ownershipModes.isEmpty()) throw new IllegalArgumentException("ownershipModes must not be empty");

        this.ownershipModes = Collections.unmodifiableList(ownershipModes);
    }
}
