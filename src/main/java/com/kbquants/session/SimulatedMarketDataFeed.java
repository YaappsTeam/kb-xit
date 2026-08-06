package com.kbquants.session;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SimulatedMarketDataFeed implements MarketDataFeed {

    private final double volatilityPercent;
    private final long tickIntervalMs;
    private final Random random;

    private double currentPrice;
    private ScheduledExecutorService executor;

    public SimulatedMarketDataFeed(double startPrice, double volatilityPercent) {
        this(startPrice, volatilityPercent, 1000, new Random());
    }

    public SimulatedMarketDataFeed(double startPrice, double volatilityPercent, long tickIntervalMs) {
        this(startPrice, volatilityPercent, tickIntervalMs, new Random());
    }

    SimulatedMarketDataFeed(double startPrice, double volatilityPercent, long tickIntervalMs, Random random) {
        if (startPrice <= 0) {
            throw new IllegalArgumentException("startPrice must be positive: " + startPrice);
        }
        this.currentPrice = startPrice;
        this.volatilityPercent = volatilityPercent;
        this.tickIntervalMs = tickIntervalMs;
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    @Override
    public void start(PriceListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "simulated-market-data");
            t.setDaemon(true);
            return t;
        });

        log.info("Starting simulated market data feed: startPrice={}, volatility={}%, interval={}ms",
                currentPrice, volatilityPercent, tickIntervalMs);

        executor.scheduleAtFixedRate(() -> simulateOneTick(listener), 0, tickIntervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (executor != null) {
            executor.shutdown();
            log.info("Simulated market data feed stopped");
        }
    }

    void simulateOneTick(PriceListener listener) {
        double change = random.nextGaussian() * (volatilityPercent / 100.0) * currentPrice;
        currentPrice = Math.max(0.01, currentPrice + change);
        listener.onPrice(currentPrice, System.currentTimeMillis());
    }
}
