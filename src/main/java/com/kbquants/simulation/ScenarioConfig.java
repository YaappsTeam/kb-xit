package com.kbquants.simulation;

import lombok.Getter;

/**
 * Configuration for scenario generation.
 * <p>
 * length                    -> number of price steps
 * drift                       -> directional bias
 * volatility                -> random movement amplitude
 * pullbackStrength   -> trend correction force
 * seed                      -> reproducible randomness
 */
@Getter
public class ScenarioConfig {

    private final int length;
    private final double drift;
    private final double volatility;
    private final double pullbackStrength;
    private final long seed;

    public ScenarioConfig(int length, double drift, double volatility, double pullbackStrength, long seed) {
        this.length = length;
        this.drift = drift;
        this.volatility = volatility;
        this.pullbackStrength = pullbackStrength;
        this.seed = seed;
    }
}
