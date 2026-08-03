package com.kbquants.simulation;


import java.util.ArrayList;
import java.util.List;

/**
 * Industry-style stochastic price generator.
 * <p>
 * Simulates:
 * - Drift
 * - Volatility
 * - Pullbacks
 * - Regime-based bias
 * - Controlled randomness
 */
public class StochasticPricePathGenerator implements PricePathGenerator {

    @Override
    public List<Double> generate(double startPrice, ScenarioConfig config, MarketRegime regime) {

        RandomProvider random = new RandomProvider(config.getSeed());

        List<Double> prices = new ArrayList<>();
        double price = startPrice;
        double lastMove = 0.0;

        prices.add(price);

        for (int i = 0; i < config.getLength(); i++) {

            double regimeDrift = adjustDriftForRegime(config.getDrift(), regime, random);
            double noise = config.getVolatility() * random.nextGaussian();
            double pullback = -config.getPullbackStrength() * lastMove;
            double move = regimeDrift + noise + pullback;

            price = Math.max(1.0, price + move); // prevent negative price
            lastMove = move;
            prices.add(price);
        }

        return prices;
    }

    private double adjustDriftForRegime(double baseDrift, MarketRegime regime, RandomProvider random) {
        return switch (regime) {
            case BULLISH -> Math.abs(baseDrift);
            case BEARISH -> -Math.abs(baseDrift);
            case CHOPPY -> 0.0;
            case RANDOM -> baseDrift * (random.nextDouble() - 0.5);
        };
    }
}
