package com.kbquants.simulation;

import java.util.Random;

/**
 * Provides deterministic randomness using seed.
 */
public class RandomProvider {

    private final Random random;

    public RandomProvider(long seed) {
        this.random = new Random(seed);
    }

    public double nextGaussian() {
        return random.nextGaussian();
    }

    public double nextDouble() {
        return random.nextDouble();
    }
}
