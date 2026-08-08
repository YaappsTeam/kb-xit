package com.kbquants.instrument;

import lombok.Getter;

import java.util.Objects;

/**
 * One tradable instrument from Upstox's instrument master.
 * <p>
 * Only the fields the exit manager actually needs are retained -- the
 * master file carries ~19 per record across 80k+ NSE instruments, and
 * holding all of them in memory serves no purpose.
 * <p>
 * lotSize is 1 for equities and the contract lot for derivatives (65 for
 * NIFTY options, for instance). freezeQuantity is the exchange's maximum
 * single-order quantity; orders above it are rejected outright, which is
 * why position sizing caps against it.
 */
@Getter
public final class Instrument {

    private final String tradingSymbol;
    private final String instrumentKey;
    private final String segment;
    private final String instrumentType;
    private final String name;
    private final int lotSize;
    private final int freezeQuantity;
    private final double tickSize;

    public Instrument(String tradingSymbol, String instrumentKey, String segment, String instrumentType,
                      String name, int lotSize, int freezeQuantity, double tickSize) {
        this.tradingSymbol = Objects.requireNonNull(tradingSymbol, "tradingSymbol must not be null");
        this.instrumentKey = Objects.requireNonNull(instrumentKey, "instrumentKey must not be null");
        this.segment = segment;
        this.instrumentType = instrumentType;
        this.name = name;
        this.lotSize = lotSize;
        this.freezeQuantity = freezeQuantity;
        this.tickSize = tickSize;
    }

    /**
     * Lot size, normalised so callers never divide by zero: the master
     * file leaves it 0 for some non-derivative records, which for sizing
     * purposes means "one unit at a time".
     */
    public int effectiveLotSize() {
        return lotSize > 0 ? lotSize : 1;
    }

    /**
     * Largest whole number of lots permitted in a single order, or 0 when
     * the instrument declares no freeze limit (equities typically don't).
     */
    public int maxLotsPerOrder() {
        return freezeQuantity > 0 ? freezeQuantity / effectiveLotSize() : 0;
    }

    @Override
    public String toString() {
        return tradingSymbol + " (" + instrumentKey + ")";
    }
}
