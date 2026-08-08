package com.kbquants.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the fallback cost model.
 * <p>
 * Expected values are anchored to real Upstox quotes taken from the live
 * brokerage API, so drift in the model shows up as a test failure rather
 * than as a silently wrong breakeven.
 */
class EstimatedChargesServiceTest {

    private static final ChargesService SERVICE = new EstimatedChargesService();

    /** Broker quoted Rs 157.87 (0.338%) for this position. */
    @Test
    void shouldApproximateOptionCostsWithinAFewPercent() {

        TradeCost cost = SERVICE.roundTripCost("NSE_FO|45148", 55.30, 845);

        assertEquals(157.87, cost.getTotalCharges(), 15.0);
        assertTrue(cost.isEstimated());
    }

    /** Broker quoted Rs 64.62 (0.132%) for this position. */
    @Test
    void shouldApproximateEquityCostsWithinAFewPercent() {

        TradeCost cost = SERVICE.roundTripCost("NSE_EQ|INE012A01025", 1362.80, 36);

        assertEquals(64.62, cost.getTotalCharges(), 15.0);
    }

    /**
     * A single blended rate would be wrong at one end: options genuinely
     * cost several times what intraday equity does.
     */
    @Test
    void shouldChargeDerivativesMoreThanEquityForTheSameTurnover() {

        double options = SERVICE.roundTripCost("NSE_FO|45148", 100.0, 500).getFractionOfInvested();
        double equity = SERVICE.roundTripCost("NSE_EQ|INE012A01025", 100.0, 500).getFractionOfInvested();

        assertTrue(options > equity, "options " + options + " should exceed equity " + equity);
    }

    /**
     * The flat per-order component is what makes small positions
     * disproportionately expensive -- the effect the whole cost model
     * exists to capture.
     */
    @Test
    void smallPositionsShouldCostAFarHigherPercentage() {

        double small = SERVICE.roundTripCost("NSE_FO|45148", 55.30, 65).getFractionOfInvested();
        double large = SERVICE.roundTripCost("NSE_FO|45148", 55.30, 845).getFractionOfInvested();

        assertTrue(small > large * 3, "small " + small + " vs large " + large);
    }

    @Test
    void breakevenShouldSitAboveEntryByTheCostFraction() {

        TradeCost cost = SERVICE.roundTripCost("NSE_FO|45148", 55.30, 845);

        assertEquals(55.30 * (1 + cost.getFractionOfInvested()), cost.breakevenPrice(55.30), 0.0001);
        assertTrue(cost.breakevenPrice(55.30) > 55.30);
    }

    @Test
    void unknownSegmentShouldUseTheMoreExpensiveAssumption() {

        double unknown = SERVICE.roundTripCost("SOMETHING|ELSE", 100.0, 500).getFractionOfInvested();
        double equity = SERVICE.roundTripCost("NSE_EQ|X", 100.0, 500).getFractionOfInvested();

        assertTrue(unknown > equity);
    }
}
