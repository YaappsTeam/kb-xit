package com.kbquants.simulation.runner;


import com.kbquants.domain.ExitModel;
import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.TradeContext;
import com.kbquants.engine.ExitEngine;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Sequential implementation of CombinationExecutor.
 * <p>
 * Executes all ExitModel × OwnershipMode combinations
 * using a single shared price path.
 * <p>
 * Engine logic remains untouched and deterministic.
 * <p>
 * Layer: simulation.runner
 */
@Slf4j
public class SequentialCombinationExecutor implements CombinationExecutor {

    private static final double DEFAULT_BASE_PRICE_MULTIPLIER = 2.0;
    private static final int DEFAULT_QUANTITY = 1;

    @Override
    public SimulationResult execute(
            List<Double> pricePath,
            SimulationRequest request
    ) {
        Objects.requireNonNull(pricePath, "pricePath must not be null");
        Objects.requireNonNull(request, "request must not be null");

        if (pricePath.isEmpty()) {
            throw new IllegalArgumentException("pricePath must not be empty");
        }

        Instant startTime = Instant.now();

        int totalCombinations =
                request.getExitModels().size() *
                        request.getOwnershipModes().size();

        log.info("Sequential simulation started. Total combinations={}", totalCombinations);
        log.info("MarketRegime={}, ExecutionMode={}",
                request.getMarketRegime(),
                request.getExecutionMode());

        List<TradeMetrics> results = new ArrayList<>();

        for (ExitModel exitModel : request.getExitModels()) {
            for (OwnershipMode ownershipMode : request.getOwnershipModes()) {

                log.debug("Running combination: ExitModel={}, OwnershipMode={}",
                        exitModel, ownershipMode);

                TradeMetrics metrics = runSingleCombination(
                        pricePath,
                        exitModel,
                        ownershipMode
                );

                results.add(metrics);

                log.debug("Completed combination: ExitModel={}, OwnershipMode={}, FinalPhase={}",
                        exitModel,
                        ownershipMode,
                        metrics.getFinalPhase());
            }
        }

        Duration duration = Duration.between(startTime, Instant.now());

        log.info("Sequential simulation completed in {} ms",
                duration.toMillis());

        return new SimulationResult(results);
    }

    /**
     * Executes one isolated ExitModel × OwnershipMode run.
     * <p>
     * Guarantees:
     * - Fresh TradeContext
     * - Fresh ExitEngine
     * - No shared state
     */
    private TradeMetrics runSingleCombination(List<Double> pricePath, ExitModel exitModel, OwnershipMode ownershipMode) {
        double entryPrice = pricePath.get(0);
        double basePrice = entryPrice * DEFAULT_BASE_PRICE_MULTIPLIER;

        String tradeId = UUID.randomUUID().toString();
        TradeContext context = new TradeContext(tradeId, entryPrice, basePrice, DEFAULT_QUANTITY, exitModel, ownershipMode);

        ExitEngine engine = new ExitEngine(context);
        MetricsCollector collector = new MetricsCollector(entryPrice);

        for (Double price : pricePath) {
            engine.onPriceUpdate(price, context);
            collector.onPrice(price, context);
            if (context.isClosed()) break;
        }

        collector.onCompletion(context);
        return collector.build(tradeId, context);
    }

//    private TradeMetrics runSingleCombination(List<Double> pricePath, ExitModel exitModel, OwnershipMode ownershipMode) {
//        double entryPrice = pricePath.get(0);
//        double basePrice = entryPrice * DEFAULT_BASE_PRICE_MULTIPLIER;
//
//        String tradeId = UUID.randomUUID().toString();
//        TradeContext context = new TradeContext(tradeId, entryPrice, basePrice, DEFAULT_QUANTITY, exitModel, ownershipMode);
//        ExitEngine engine = new ExitEngine(context);
//
//        for (Double price : pricePath) {
//            engine.onPriceUpdate(price, context);
//            if (context.isClosed()) break; // respect lifecycle termination
//        }
//
//        return new TradeMetrics(exitModel, ownershipMode, context.getCurrentStopLoss(), context.getCurrentPhase());
//    }
}
