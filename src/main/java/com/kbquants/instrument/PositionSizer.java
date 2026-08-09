package com.kbquants.instrument;

import com.kbquants.domain.ActiveLadder;
import com.kbquants.domain.RiskSettings;
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
    private final RiskSettings riskSettings;
    private final ActiveLadder activeLadder;

    public PositionSizer(double capitalPerTrade) {
        this(capitalPerTrade, new RiskSettings(0), null);
    }

    public PositionSizer(double capitalPerTrade, double maxRiskPerTrade, ActiveLadder activeLadder) {
        this(capitalPerTrade, new RiskSettings(maxRiskPerTrade), activeLadder);
    }

    /**
     * @param riskSettings rupees to lose if the hard stop is hit, or 0 to
     *                     size on capital alone. Read per call rather than
     *                     copied, so a change from Telegram applies to the
     *                     next trade.
     * @param activeLadder supplies the hard stop the risk is measured
     *                     against; it moves with the selected set
     */
    public PositionSizer(double capitalPerTrade, RiskSettings riskSettings, ActiveLadder activeLadder) {
        if (capitalPerTrade <= 0) {
            throw new IllegalArgumentException("capitalPerTrade must be positive: " + capitalPerTrade);
        }
        if (riskSettings == null) {
            throw new IllegalArgumentException("riskSettings must not be null");
        }
        if (riskSettings.isEnabled() && activeLadder == null) {
            throw new IllegalArgumentException("a risk ceiling needs an activeLadder to read the hard stop from");
        }
        this.capitalPerTrade = capitalPerTrade;
        this.riskSettings = riskSettings;
        this.activeLadder = activeLadder;
    }

    public Result size(Instrument instrument, double price) {

        if (price <= 0) {
            return Result.rejected("no usable price (got " + price + ")");
        }

        int lotSize = instrument.effectiveLotSize();
        double contractCost = price * lotSize;

        int lotsFromCapital = (int) Math.floor(capitalPerTrade / contractCost);
        if (lotsFromCapital < 1) {
            return Result.rejected(String.format(
                    "one lot of %s costs %.2f (%d x %.2f) which exceeds capital per trade %.2f",
                    instrument.getTradingSymbol(), contractCost, lotSize, price, capitalPerTrade));
        }

        // Risk, not capital, is what "preserve capital" actually constrains.
        // A wide stop is not the same as a large loss: 40% of a small
        // position loses less than 15% of a large one, and only the wide
        // stop survives the noise an option premium generates. So size the
        // position from the loss it would take rather than narrowing the
        // stop until the rupees look tolerable.
        int lots = lotsFromCapital;
        boolean limitedByRisk = false;

        if (riskSettings.isEnabled()) {
            double riskPerLot = contractCost * activeLadder.get().getHardStopPercent();
            int lotsFromRisk = (int) Math.floor(riskSettings.getMaxRiskPerTrade() / riskPerLot);

            if (lotsFromRisk < 1) {
                return Result.rejected(String.format(
                        "one lot of %s risks %.2f at the %.0f%% hard stop, above max risk per trade %.2f",
                        instrument.getTradingSymbol(), riskPerLot,
                        activeLadder.get().getHardStopPercent() * 100, riskSettings.getMaxRiskPerTrade()));
            }
            if (lotsFromRisk < lots) {
                lots = lotsFromRisk;
                limitedByRisk = true;
            }
        }

        int maxLots = instrument.maxLotsPerOrder();
        boolean capped = maxLots > 0 && lots > maxLots;
        if (capped) {
            lots = maxLots;
            limitedByRisk = false;
        }

        double riskAtStop = riskSettings.isEnabled()
                ? lots * contractCost * activeLadder.get().getHardStopPercent()
                : 0;

        return Result.accepted(lots * lotSize, lots, lots * contractCost, capped, limitedByRisk, riskAtStop);
    }

    @Getter
    public static final class Result {

        private final boolean accepted;
        private final int quantity;
        private final int lots;
        private final double deployedCapital;
        private final boolean cappedByFreezeLimit;
        private final boolean limitedByRisk;
        private final double riskAtHardStop;
        private final String rejectionReason;

        private Result(boolean accepted, int quantity, int lots, double deployedCapital,
                       boolean cappedByFreezeLimit, boolean limitedByRisk, double riskAtHardStop,
                       String rejectionReason) {
            this.accepted = accepted;
            this.quantity = quantity;
            this.lots = lots;
            this.deployedCapital = deployedCapital;
            this.cappedByFreezeLimit = cappedByFreezeLimit;
            this.limitedByRisk = limitedByRisk;
            this.riskAtHardStop = riskAtHardStop;
            this.rejectionReason = rejectionReason;
        }

        static Result accepted(int quantity, int lots, double deployedCapital, boolean capped,
                               boolean limitedByRisk, double riskAtHardStop) {
            return new Result(true, quantity, lots, deployedCapital, capped, limitedByRisk, riskAtHardStop, null);
        }

        static Result rejected(String reason) {
            return new Result(false, 0, 0, 0, false, false, 0, reason);
        }
    }
}
