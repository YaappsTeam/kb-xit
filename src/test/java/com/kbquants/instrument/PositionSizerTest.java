package com.kbquants.instrument;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for PositionSizer.
 * <p>
 * Lot sizes and freeze quantities are the real ones from Upstox's NSE
 * master: NIFTY options trade in lots of 65 with a freeze quantity of
 * 1755, i.e. 27 lots per order.
 */
class PositionSizerTest {

    private static Instrument equity() {
        return new Instrument("ACC", "NSE_EQ|INE012A01025", "NSE_EQ", "EQ", "ACC LIMITED", 1, 0, 0.10);
    }

    private static Instrument niftyOption() {
        return new Instrument("NIFTY 25000 CE 18 AUG 26", "NSE_FO|45148", "NSE_FO", "CE", "NIFTY", 65, 1755, 0.05);
    }

    @Test
    void shouldSizeEquityByCapitalDividedByPrice() {

        PositionSizer.Result result = new PositionSizer(50_000).size(equity(), 1850.50);

        assertTrue(result.isAccepted());
        assertEquals(27, result.getQuantity());
        assertEquals(27, result.getLots());
        assertFalse(result.isCappedByFreezeLimit());
    }

    @Test
    void shouldNeverExceedCapital() {

        PositionSizer.Result result = new PositionSizer(50_000).size(equity(), 1850.50);

        assertTrue(result.getDeployedCapital() <= 50_000);
    }

    /**
     * The core of the lots question: quantity must be a whole multiple of
     * the contract lot, never an arbitrary integer.
     */
    @Test
    void shouldSizeOptionInWholeLots() {

        PositionSizer.Result result = new PositionSizer(50_000).size(niftyOption(), 150.0);

        assertTrue(result.isAccepted());
        assertEquals(5, result.getLots());
        assertEquals(325, result.getQuantity());
        assertEquals(0, result.getQuantity() % 65);
    }

    @Test
    void shouldRoundDownRatherThanOverspend() {

        // 1 lot costs 620 x 65 = 40,300; two lots would need 80,600.
        PositionSizer.Result result = new PositionSizer(50_000).size(niftyOption(), 620.0);

        assertEquals(1, result.getLots());
        assertEquals(65, result.getQuantity());
    }

    /**
     * Expiry-day case: capital would buy far more than the exchange allows
     * in one order, so the order is capped rather than sliced.
     */
    @Test
    void shouldCapAtExchangeFreezeLimit() {

        PositionSizer.Result result = new PositionSizer(10_000_000).size(niftyOption(), 150.0);

        assertTrue(result.isAccepted());
        assertTrue(result.isCappedByFreezeLimit());
        assertEquals(27, result.getLots());
        assertEquals(1755, result.getQuantity());
    }

    @Test
    void shouldNotFlagCappingWhenBelowFreezeLimit() {

        PositionSizer.Result result = new PositionSizer(50_000).size(niftyOption(), 150.0);

        assertFalse(result.isCappedByFreezeLimit());
    }

    /**
     * Refusing beats silently sizing zero -- a zero-quantity trade would be
     * tracked as if it were real.
     */
    @Test
    void shouldRejectWhenCapitalCannotCoverOneLot() {

        PositionSizer.Result result = new PositionSizer(10_000).size(niftyOption(), 620.0);

        assertFalse(result.isAccepted());
        assertEquals(0, result.getQuantity());
        assertTrue(result.getRejectionReason().contains("exceeds capital per trade"));
    }

    @Test
    void shouldRejectNonPositivePrice() {
        assertFalse(new PositionSizer(50_000).size(equity(), 0).isAccepted());
    }

    @Test
    void shouldTreatMissingLotSizeAsOne() {

        Instrument noLotSize = new Instrument("X", "NSE_EQ|X", "NSE_EQ", "EQ", "X", 0, 0, 0.05);

        assertEquals(100, new PositionSizer(1000).size(noLotSize, 10.0).getQuantity());
    }

    @Test
    void shouldRejectNonPositiveCapital() {
        assertThrows(IllegalArgumentException.class, () -> new PositionSizer(0));
    }
}
