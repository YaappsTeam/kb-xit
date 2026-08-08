package com.kbquants.session;

/**
 * Supplies the round-trip cost of a trade, so that breakeven can be set at
 * the point where the position is genuinely square rather than at the raw
 * entry price.
 * <p>
 * Never returns empty: a trade must always have a breakeven, so
 * implementations that cannot reach the broker fall back to an estimate and
 * mark it as such via {@link TradeCost#isEstimated()}. Blocking a trade
 * because a charges lookup failed would be a worse outcome than using an
 * approximate breakeven.
 */
public interface ChargesService {

    TradeCost roundTripCost(String instrumentKey, double price, int quantity);
}
