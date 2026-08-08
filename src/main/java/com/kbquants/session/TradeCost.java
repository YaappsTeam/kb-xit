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
     * <p>
     * Sell-side charges scale with the exit price, which is unknown at
     * entry, so they are computed at the entry price. Near breakeven that
     * understates by about Rs 0.31 on a Rs 46.7k option position (0.0007%
     * of capital), which is why the breakeven figure itself is sound even
     * though the same approximation drifts to Rs 89 at a +100% exit.
     */
    public double breakevenPrice(double entryPrice) {
        return entryPrice * (1 + getFractionOfInvested());
    }

    /**
     * Breakeven rounded up to a price that can actually be traded.
     * <p>
     * Matters most where it is least obvious: on a Rs 1 option premium the
     * tick is Rs 0.05, so a computed breakeven of 1.0253 is unreachable --
     * the first sellable price above it is 1.05, making the real breakeven
     * +5% rather than +2.53%. Rounding down, or not rounding at all, would
     * declare breakeven at a price no one can exit at.
     */
    public double breakevenPrice(double entryPrice, double tickSize) {
        double exact = breakevenPrice(entryPrice);
        if (tickSize <= 0) {
            return exact;
        }
        return Math.ceil(exact / tickSize - 1e-9) * tickSize;
    }

    /** Net profit on exiting at this price, after all charges. */
    public double netProfitAt(double entryPrice, double exitPrice, int quantity) {
        return (exitPrice - entryPrice) * quantity - getTotalCharges();
    }

    @Override
    public String toString() {
        return String.format("%s round-trip cost %.2f on %.2f invested (%.3f%%)",
                estimated ? "modelled" : "broker-quoted",
                getTotalCharges(), investedAmount, getFractionOfInvested() * 100);
    }
}
