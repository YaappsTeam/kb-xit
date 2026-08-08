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

    /**
     * Estimated round-trip cost at entry time. The sell side is priced at
     * the entry price, since the exit price is not yet known.
     */
    TradeCost roundTripCost(String instrumentKey, double price, int quantity);

    /**
     * Actual round-trip cost once the exit price is known: buy side priced
     * at entry, sell side at the real exit. This is what turns the running
     * estimate into a settled figure worth reporting as final.
     */
    TradeCost settledCost(String instrumentKey, double entryPrice, double exitPrice, int quantity);
}
