package com.kbquants.instrument;

import com.kbquants.domain.ActiveLadder;
import com.kbquants.domain.MilestoneLadder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Risk-based position sizing.
 * <p>
 * The point is that a wide stop and a large loss are different things: 40%
 * of a small position loses less than 15% of a large one, and only the wide
 * stop survives the noise an option premium generates. So the position is
 * sized from the loss it would take rather than the stop being narrowed
 * until the rupees look tolerable.
 */
class PositionSizerRiskTest {

    private static final Instrument NIFTY_CE =
            new Instrument("NIFTY 25000 CE 18 AUG 26", "NSE_FO|45148", "NSE_FO", "CE", "NIFTY", 65, 1755, 5.0);

    private static ActiveLadder options() {
        return new ActiveLadder(MilestoneLadder.optionsLadder());
    }

    /**
     * Rs 50k capital with a 40% hard stop risks Rs 20k -- the number that
     * prompted this. Capping risk at Rs 5k cuts the position instead of the
     * stop.
     */
    @Test
    void shouldSizeFromRiskWhenRiskBindsBeforeCapital() {

        PositionSizer.Result result = new PositionSizer(50_000, 5_000, options()).size(NIFTY_CE, 55.30);

        assertTrue(result.isAccepted());
        assertTrue(result.isLimitedByRisk());
        assertTrue(result.getRiskAtHardStop() <= 5_000,
                "risk " + result.getRiskAtHardStop() + " should not exceed the cap");
        assertEquals(0, result.getQuantity() % 65, "must stay in whole lots");
    }

    @Test
    void riskSizedPositionShouldBeSmallerThanCapitalSized() {

        int withRisk = new PositionSizer(50_000, 5_000, options()).size(NIFTY_CE, 55.30).getQuantity();
        int withoutRisk = new PositionSizer(50_000).size(NIFTY_CE, 55.30).getQuantity();

        assertTrue(withRisk < withoutRisk, withRisk + " should be below " + withoutRisk);
    }

    @Test
    void shouldFallBackToCapitalWhenCapitalBindsFirst() {

        // Rs 100k of risk allowance cannot be reached within Rs 50k of capital.
        PositionSizer.Result result = new PositionSizer(50_000, 100_000, options()).size(NIFTY_CE, 55.30);

        assertFalse(result.isLimitedByRisk());
        assertEquals(new PositionSizer(50_000).size(NIFTY_CE, 55.30).getQuantity(), result.getQuantity());
    }

    /**
     * The hard stop comes from the active set, so switching sets changes
     * how much a lot risks and therefore how many lots fit.
     */
    @Test
    void shouldFollowTheActiveLaddersHardStop() {

        ActiveLadder ladder = options();
        int atFortyPercent = new PositionSizer(1_000_000, 5_000, ladder).size(NIFTY_CE, 55.30).getQuantity();

        ladder.set(MilestoneLadder.equityLadder()); // 20% stop -> same risk buys more
        int atTwentyPercent = new PositionSizer(1_000_000, 5_000, ladder).size(NIFTY_CE, 55.30).getQuantity();

        assertTrue(atTwentyPercent > atFortyPercent,
                atTwentyPercent + " should exceed " + atFortyPercent);
    }

    @Test
    void shouldRejectWhenEvenOneLotExceedsTheRiskAllowance() {

        PositionSizer.Result result = new PositionSizer(50_000, 100, options()).size(NIFTY_CE, 55.30);

        assertFalse(result.isAccepted());
        assertTrue(result.getRejectionReason().contains("above max risk per trade"));
    }

    /**
     * The freeze limit is an exchange rule and overrides both dials.
     */
    @Test
    void freezeLimitShouldStillCapEverything() {

        PositionSizer.Result result = new PositionSizer(10_000_000, 10_000_000, options()).size(NIFTY_CE, 55.30);

        assertTrue(result.isCappedByFreezeLimit());
        assertEquals(27, result.getLots());
        assertFalse(result.isLimitedByRisk(), "freeze cap, not risk, is the binding constraint here");
    }

    @Test
    void shouldNotReportRiskWhenNoRiskCapIsConfigured() {
        assertEquals(0, new PositionSizer(50_000).size(NIFTY_CE, 55.30).getRiskAtHardStop());
    }

    @Test
    void shouldRejectRiskCapWithoutALadderToMeasureAgainst() {
        assertThrows(IllegalArgumentException.class, () -> new PositionSizer(50_000, 5_000, null));
    }
}
