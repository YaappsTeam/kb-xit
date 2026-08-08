package com.kbquants.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for TradeCost, in particular the tick rounding that expiry-day
 * premiums make necessary.
 */
class TradeCostTest {

    @Test
    void breakevenShouldSitAboveEntryByTheCostFraction() {

        TradeCost cost = new TradeCost(80.0, 80.0, 16_000.0, false); // 1% round trip

        assertEquals(101.0, cost.breakevenPrice(100.0), 0.0001);
    }

    /**
     * The expiry-day case. On a Rs 1 premium with a Rs 0.05 tick, a
     * computed breakeven of 1.0253 is not a price anyone can sell at -- the
     * first reachable one is 1.05, so the real breakeven is +5%, not
     * +2.53%. Rounding down would declare breakeven at an unreachable price.
     */
    @Test
    void breakevenShouldRoundUpToTheNextTradableTick() {

        // 27 lots of 65 at Rs 1 = Rs 1,755 invested; ~Rs 44 round trip.
        TradeCost cost = new TradeCost(22.0, 22.0, 1_755.0, true);

        double exact = cost.breakevenPrice(1.00);
        double tradable = cost.breakevenPrice(1.00, 0.05);

        assertTrue(exact > 1.02 && exact < 1.03, "exact was " + exact);
        assertEquals(1.05, tradable, 0.0001);
    }

    @Test
    void breakevenAlreadyOnATickShouldNotBeNudgedUp() {

        TradeCost cost = new TradeCost(80.0, 80.0, 16_000.0, false); // exactly 1% -> 101.00

        assertEquals(101.00, cost.breakevenPrice(100.0, 0.05), 0.0001);
    }

    @Test
    void unknownTickSizeShouldLeaveBreakevenUnrounded() {

        TradeCost cost = new TradeCost(22.0, 22.0, 1_755.0, true);

        assertEquals(cost.breakevenPrice(1.00), cost.breakevenPrice(1.00, 0), 0.0001);
    }

    @Test
    void netProfitShouldSubtractAllCharges() {

        TradeCost cost = new TradeCost(50.0, 60.0, 10_000.0, false);

        // (110 - 100) x 100 = 1000 gross, minus 110 charges
        assertEquals(890.0, cost.netProfitAt(100.0, 110.0, 100), 0.0001);
    }

    @Test
    void netProfitShouldGoNegativeWhenCostsExceedAThinGain() {

        TradeCost cost = new TradeCost(28.0, 28.0, 3_594.0, true);

        // A gross gain of Rs 32.50 does not cover Rs 56 of charges.
        assertTrue(cost.netProfitAt(55.30, 55.80, 65) < 0);
    }

    @Test
    void shouldLabelModelledCostsDistinctlyFromBrokerQuoted() {

        assertTrue(new TradeCost(20, 20, 1000, true).toString().contains("modelled"));
        assertTrue(new TradeCost(20, 20, 1000, false).toString().contains("broker-quoted"));
    }
}
