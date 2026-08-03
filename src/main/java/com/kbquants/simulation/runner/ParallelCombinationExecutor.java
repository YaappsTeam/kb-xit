package com.kbquants.simulation.runner;


import com.kbquants.domain.ExitModel;
import com.kbquants.domain.OwnershipMode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Parallel implementation of CombinationExecutor.
 * <p>
 * Responsibilities:
 * - Execute ExitModel × OwnershipMode combinations in parallel.
 * - Ensure isolation of TradeContext per combination.
 * - Preserve deterministic result ordering.
 * <p>
 * Layer: simulation.runner
 */
@Slf4j
public class ParallelCombinationExecutor implements CombinationExecutor {

    private final ExecutorService executorService;

    public ParallelCombinationExecutor(int threadPoolSize) {
        if (threadPoolSize <= 0) throw new IllegalArgumentException("threadPoolSize must be positive");
        this.executorService = Executors.newFixedThreadPool(threadPoolSize);
    }

    @Override
    public SimulationResult execute(List<Double> pricePath, SimulationRequest request) {

        Objects.requireNonNull(pricePath, "pricePath must not be null");
        Objects.requireNonNull(request, "request must not be null");

        if (pricePath.isEmpty()) throw new IllegalArgumentException("pricePath must not be empty");
        log.info("Parallel execution started");

        List<Callable<TradeMetrics>> tasks = new ArrayList<>();

        for (ExitModel exitModel : request.getExitModels()) {
            for (OwnershipMode ownershipMode : request.getOwnershipModes()) {

                tasks.add(() -> {
                    SequentialCombinationExecutor sequential = new SequentialCombinationExecutor();

                    // Reuse internal logic for single combination
                    return sequential.execute(pricePath, new SimulationRequest(request.getMarketRegime(), request.getScenarioConfig(),
                                    List.of(exitModel), List.of(ownershipMode), ExecutionMode.SEQUENTIAL))
                            .getTradeMetricsList()
                            .get(0);
                });
            }
        }

        List<TradeMetrics> results = new ArrayList<>();

        try {
            List<Future<TradeMetrics>> futures = executorService.invokeAll(tasks);
            for (Future<TradeMetrics> future : futures) {
                results.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel execution interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Parallel execution failed", e);
        }

        log.info("Parallel execution completed");
        return new SimulationResult(results);
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
