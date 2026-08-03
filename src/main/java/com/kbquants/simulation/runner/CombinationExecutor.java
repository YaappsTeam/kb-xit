package com.kbquants.simulation.runner;

import java.util.List;

public interface CombinationExecutor {
    SimulationResult execute(
            List<Double> pricePath, SimulationRequest request
    );
}
