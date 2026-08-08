package com.kbquants.session;

import lombok.Getter;

/**
 * The round-trip cost of a trade -- brokerage, STT, exchange transaction
 * charges, GST, stamp duty -- and the breakeven price that follows from it.
 * <p>
 * Expressed as a fraction of the money actually invested rather than as a
 * per-unit amount, because brokerage is flat (Rs 20 per executed order) and
 * everything else is proportional. That mix means the cost percentage is
 * not a constant: measured against real Upstox charges it ranges from
 * ~0.13% on a Rs 49k equity position to ~1.55% on a Rs 3.6k option
 * position. A fixed percentage would be wrong at both ends, which is why
 * this is computed per trade.
 */
@Getter
public final class TradeCost {

    private final double buyCharges;
    private final double sellCharges;
    private final double investedAmount;
    private final boolean estimated;

    public TradeCost(double buyCharges, double sellCharges, double investedAmount, boolean estimated) {
        if (investedAmount <= 0) {
            throw new IllegalArgumentException("investedAmount must be positive: " + investedAmount);
        }
        this.buyCharges = buyCharges;
        this.sellCharges = sellCharges;
        this.investedAmount = investedAmount;
        this.estimated = estimated;
    }

    public double getTotalCharges() {
        return buyCharges + sellCharges;
    }

    /** Round-trip cost as a fraction of invested capital, e.g. 0.00338 for 0.338%. */
    public double getFractionOfInvested() {
        return getTotalCharges() / investedAmount;
    }

    /**
     * The price at which the position breaks even net of all charges --
     * the point where "profit" starts meaning money you actually keep.
     */
    public double breakevenPrice(double entryPrice) {
        return entryPrice * (1 + getFractionOfInvested());
    }

    /**
     * Sell-side charges scale with the exit price, which is unknown at
     * entry, so they are computed at the entry price instead. The
     * understatement is negligible -- on a Rs 46.7k option position the
     * difference between charging at entry and at the resulting breakeven
     * is about Rs 0.24, or 0.0005% of invested.
     */
    @Override
    public String toString() {
        return String.format("%s round-trip cost %.2f on %.2f invested (%.3f%%)",
                estimated ? "estimated" : "broker-quoted",
                getTotalCharges(), investedAmount, getFractionOfInvested() * 100);
    }
}
