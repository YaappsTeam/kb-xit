package com.kbquants.session;

/**
 * Cost model for when the broker cannot be asked -- simulated mode, or as
 * the fallback when a live charges lookup fails.
 * <p>
 * Models the shape of real Indian brokerage rather than a flat percentage,
 * because the flat component is exactly what makes small positions
 * expensive: a fixed fee per executed order plus a proportional slice for
 * STT, exchange charges, GST and stamp duty.
 * <p>
 * The proportional rate differs sharply by segment -- options carry far
 * heavier STT and exchange charges than intraday equity -- so a single
 * blended rate is wrong at one end or the other. Calibrated against
 * Upstox's own quotes: a Rs 46.7k option position costs 0.338% round trip
 * and a Rs 49k equity position 0.132%. Using the option rate for equities
 * overstates their cost by roughly 2.4x, which would push every paper
 * breakeven too high.
 */
public final class EstimatedChargesService implements ChargesService {

    private static final double DEFAULT_FLAT_PER_ORDER = 20.0;

    /** Per side, calibrated on NSE F&O quotes (STT, exchange charges, GST, stamp). */
    private static final double DERIVATIVES_PROPORTIONAL = 0.00126;

    /** Per side, calibrated on NSE intraday equity, where STT and exchange charges are far lower. */
    private static final double EQUITY_PROPORTIONAL = 0.00025;

    private final double flatPerOrder;
    private final Double proportionalOverride;

    public EstimatedChargesService() {
        this.flatPerOrder = DEFAULT_FLAT_PER_ORDER;
        this.proportionalOverride = null;
    }

    public EstimatedChargesService(double flatPerOrder, double proportionalFraction) {
        this.flatPerOrder = flatPerOrder;
        this.proportionalOverride = proportionalFraction;
    }

    @Override
    public TradeCost roundTripCost(String instrumentKey, double price, int quantity) {
        return settledCost(instrumentKey, price, price, quantity);
    }

    @Override
    public TradeCost settledCost(String instrumentKey, double entryPrice, double exitPrice, int quantity) {
        double proportional = proportionalFor(instrumentKey);
        double invested = entryPrice * quantity;
        double exitValue = exitPrice * quantity;
        return new TradeCost(
                flatPerOrder + invested * proportional,
                flatPerOrder + exitValue * proportional,
                invested,
                true);
    }

    /**
     * Anything that is not recognisably cash equity is treated as a
     * derivative -- erring towards the more expensive assumption, since an
     * overstated breakeven merely delays a profit notification whereas an
     * understated one reports profit that is not there.
     */
    private double proportionalFor(String instrumentKey) {
        if (proportionalOverride != null) {
            return proportionalOverride;
        }
        return instrumentKey != null && instrumentKey.contains("_EQ")
                ? EQUITY_PROPORTIONAL
                : DERIVATIVES_PROPORTIONAL;
    }
}
