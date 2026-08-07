package com.kbquants.instrument;

import lombok.Getter;

/**
 * Turns a capital allowance into a tradable quantity, in whole lots.
 * <p>
 * Quantity cannot be a free integer for derivatives: it must be a multiple
 * of the contract lot (65 for NIFTY options, and lot sizes range from 25 to
 * 625+ across NSE F&O), and it must not exceed the exchange's freeze limit
 * for a single order. Equities fall out of the same formula because their
 * lot size is 1.
 * <p>
 * When capital exceeds the freeze limit -- routine on expiry days -- the
 * order is capped at the limit rather than sliced into several orders.
 * Slicing is a deliberate future enhancement, not an oversight: multiple
 * orders mean multiple fills at different prices, which the exit engine's
 * single-entry-price model does not represent today.
 */
public final class PositionSizer {

    private final double capitalPerTrade;

    public PositionSizer(double capitalPerTrade) {
        if (capitalPerTrade <= 0) {
            throw new IllegalArgumentException("capitalPerTrade must be positive: " + capitalPerTrade);
        }
        this.capitalPerTrade = capitalPerTrade;
    }

    public Result size(Instrument instrument, double price) {

        if (price <= 0) {
            return Result.rejected("no usable price (got " + price + ")");
        }

        int lotSize = instrument.effectiveLotSize();
        double contractCost = price * lotSize;
        int lots = (int) Math.floor(capitalPerTrade / contractCost);

        if (lots < 1) {
            return Result.rejected(String.format(
                    "one lot of %s costs %.2f (%d x %.2f) which exceeds capital per trade %.2f",
                    instrument.getTradingSymbol(), contractCost, lotSize, price, capitalPerTrade));
        }

        int maxLots = instrument.maxLotsPerOrder();
        boolean capped = maxLots > 0 && lots > maxLots;
        if (capped) {
            lots = maxLots;
        }

        return Result.accepted(lots * lotSize, lots, lots * contractCost, capped);
    }

    @Getter
    public static final class Result {

        private final boolean accepted;
        private final int quantity;
        private final int lots;
        private final double deployedCapital;
        private final boolean cappedByFreezeLimit;
        private final String rejectionReason;

        private Result(boolean accepted, int quantity, int lots, double deployedCapital,
                       boolean cappedByFreezeLimit, String rejectionReason) {
            this.accepted = accepted;
            this.quantity = quantity;
            this.lots = lots;
            this.deployedCapital = deployedCapital;
            this.cappedByFreezeLimit = cappedByFreezeLimit;
            this.rejectionReason = rejectionReason;
        }

        static Result accepted(int quantity, int lots, double deployedCapital, boolean capped) {
            return new Result(true, quantity, lots, deployedCapital, capped, null);
        }

        static Result rejected(String reason) {
            return new Result(false, 0, 0, 0, false, reason);
        }
    }
}
