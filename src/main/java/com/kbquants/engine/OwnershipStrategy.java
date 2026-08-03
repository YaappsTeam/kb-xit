package com.kbquants.engine;

import com.kbquants.domain.TradeContext;

/**
 * Defines how profit ownership is calculated and applied.
 */
public interface OwnershipStrategy {

    void apply(double currentPrice, TradeContext context);
}
