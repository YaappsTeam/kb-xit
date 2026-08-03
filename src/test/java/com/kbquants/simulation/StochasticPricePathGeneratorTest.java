package com.kbquants.simulation;


import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StochasticPricePathGeneratorTest {

    @Test
    void shouldGenerateCorrectLength() {

        PricePathGenerator generator = new StochasticPricePathGenerator();

        ScenarioConfig config = new ScenarioConfig(50, 0.5, 1.0, 0.1, 42L);

        List<Double> prices = generator.generate(100.0, config, MarketRegime.BULLISH);

        assertEquals(51, prices.size());
    }

    @Test
    void sameSeedShouldGenerateSamePathForBullishRegime() {

        PricePathGenerator generator = new StochasticPricePathGenerator();

        ScenarioConfig config = new ScenarioConfig(50, 0.5, 1.0, 0.1, 42L);

        List<Double> p1 = generator.generate(100.0, config, MarketRegime.BULLISH);

        List<Double> p2 = generator.generate(100.0, config, MarketRegime.BULLISH);

        assertEquals(p1, p2);
    }

    @Test
    void bullishShouldTrendHigherOnAverage() {

        PricePathGenerator generator = new StochasticPricePathGenerator();

        ScenarioConfig config = new ScenarioConfig(100, 0.5, 0.5, 0.05, 42L);

        List<Double> prices = generator.generate(100.0, config, MarketRegime.BULLISH);

        assertTrue(prices.get(prices.size() - 1) > 100.0);
    }

    @Test
    void bearishShouldTrendLowerOnAverage() {

        PricePathGenerator generator = new StochasticPricePathGenerator();

        ScenarioConfig config = new ScenarioConfig(100, 0.5, 0.5, 0.05, 42L);

        List<Double> prices = generator.generate(100.0, config, MarketRegime.BEARISH);

        assertTrue(prices.get(prices.size() - 1) < 100.0);
    }

    @Test
    void choppyShouldNotDriftStrongly() {

        PricePathGenerator generator = new StochasticPricePathGenerator();

        ScenarioConfig config = new ScenarioConfig(200, 0.5, 0.8, 0.2, 42L);

        List<Double> prices = generator.generate(100.0, config, MarketRegime.CHOPPY);

        double finalPrice = prices.get(prices.size() - 1);

        // Should not drift too far from start
        assertTrue(Math.abs(finalPrice - 100.0) < 50);
    }

    @Test
    void randomRegimeShouldBeDeterministicWithSameSeed() {

        PricePathGenerator generator = new StochasticPricePathGenerator();
        ScenarioConfig config = new ScenarioConfig(100, 0.5, 1.0, 0.1, 99L);

        List<Double> p1 = generator.generate(100.0, config, MarketRegime.RANDOM);
        List<Double> p2 = generator.generate(100.0, config, MarketRegime.RANDOM);

        assertEquals(p1, p2);
    }

    @Test
    void priceShouldNeverGoNegative() {

        PricePathGenerator generator = new StochasticPricePathGenerator();
        ScenarioConfig config = new ScenarioConfig(200, 0.0, 50.0, 0.0, 42L);

        List<Double> prices = generator.generate(10.0, config, MarketRegime.CHOPPY);

        for (Double price : prices) {
            assertTrue(price >= 1.0);
        }
    }

    @Test
    void zeroLengthShouldReturnSingleStartPrice() {

        PricePathGenerator generator = new StochasticPricePathGenerator();
        ScenarioConfig config = new ScenarioConfig(0, 0.5, 1.0, 0.1, 42L);

        List<Double> prices = generator.generate(100.0, config, MarketRegime.BULLISH);

        assertEquals(1, prices.size());
        assertEquals(100.0, prices.get(0));
    }
}
