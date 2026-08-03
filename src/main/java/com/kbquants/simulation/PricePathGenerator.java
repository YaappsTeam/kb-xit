package com.kbquants.simulation;

import java.util.List;

/**
 * Generate price path based on regime and configuration
 */
public interface PricePathGenerator {
    List<Double> generate(double startPrice, ScenarioConfig config, MarketRegime regime);
}
