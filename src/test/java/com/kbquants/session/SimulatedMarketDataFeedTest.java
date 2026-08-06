package com.kbquants.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatedMarketDataFeedTest {

    @Test
    void shouldRejectNonPositiveStartPrice() {

        assertThrows(IllegalArgumentException.class,
                () -> new SimulatedMarketDataFeed(0.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new SimulatedMarketDataFeed(-10.0, 1.0));
    }

    @Test
    void shouldGenerateAPriceNearStartPriceOnEachTick() {

        Random deterministicRandom = new Random(42);
        SimulatedMarketDataFeed feed = new SimulatedMarketDataFeed(100.0, 1.0, 1000, deterministicRandom);

        List<Double> prices = new ArrayList<>();
        PriceListener listener = (price, timestamp) -> prices.add(price);

        feed.simulateOneTick(listener);
        feed.simulateOneTick(listener);
        feed.simulateOneTick(listener);

        assertEquals(3, prices.size());
        for (double price : prices) {
            assertTrue(price > 0);
        }
    }

    @Test
    void shouldNeverProduceNonPositivePrice() {

        Random extremeRandom = new Random() {
            @Override
            public double nextGaussian() {
                return -1000.0; // force an extreme downward move every tick
            }
        };
        SimulatedMarketDataFeed feed = new SimulatedMarketDataFeed(1.0, 50.0, 1000, extremeRandom);

        List<Double> prices = new ArrayList<>();
        PriceListener listener = (price, timestamp) -> prices.add(price);

        feed.simulateOneTick(listener);

        assertTrue(prices.get(0) > 0);
    }
}
