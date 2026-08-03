package com.kbquants.session;


import java.util.List;

/**
 * DeterministicSimulationFeed
 * <p>
 * Emits a predefined list of prices sequentially.
 * Used for structural validation of ExitEngine behavior.
 * <p>
 * This feed:
 * - Has no randomness
 * - Has no concurrency
 * - Is fully deterministic
 */
public class DeterministicSimulationFeed implements MarketDataFeed {

    private final List<Double> priceSeries;

    public DeterministicSimulationFeed(List<Double> priceSeries) {
        this.priceSeries = priceSeries;
    }

    @Override
    public void start(PriceListener listener) {

        long timestamp = System.currentTimeMillis();

        for (Double price : priceSeries) {
            listener.onPrice(price, timestamp++);
        }
    }
}
