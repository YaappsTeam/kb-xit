package com.kbquants.instrument;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for StrikeWindow: pure computation, no I/O.
 */
class StrikeWindowTest {

    private static final LocalDate NEAR_EXPIRY = LocalDate.of(2026, 8, 20);
    private static final LocalDate FAR_EXPIRY = LocalDate.of(2026, 8, 27);

    private static Instrument option(double strike, String type, LocalDate expiry) {
        String symbol = "NIFTY " + (int) strike + " " + type + " " + expiry;
        return new Instrument(symbol, "NSE_FO|" + symbol, "NSE_FO", type, "NIFTY", 65, 1800, 0.05,
                strike, expiry);
    }

    /** A chain from 24500 to 25500 in steps of 50 (21 strikes), both CE and PE, one expiry. */
    private static List<Instrument> chain(LocalDate expiry) {
        List<Instrument> options = new ArrayList<>();
        for (double strike = 24500; strike <= 25500; strike += 50) {
            options.add(option(strike, "CE", expiry));
            options.add(option(strike, "PE", expiry));
        }
        return options;
    }

    // ---- nearestExpiry ----

    @Test
    void nearestExpiryShouldPickTheEarliestOnOrAfterToday() {

        List<Instrument> options = new ArrayList<>();
        options.addAll(chain(NEAR_EXPIRY));
        options.addAll(chain(FAR_EXPIRY));

        Optional<LocalDate> result = StrikeWindow.nearestExpiry(options, LocalDate.of(2026, 8, 15));

        assertEquals(Optional.of(NEAR_EXPIRY), result);
    }

    @Test
    void nearestExpiryShouldSkipExpiriesBeforeToday() {

        List<Instrument> options = chain(NEAR_EXPIRY);

        Optional<LocalDate> result = StrikeWindow.nearestExpiry(options, NEAR_EXPIRY.plusDays(1));

        assertEquals(Optional.empty(), result);
    }

    @Test
    void nearestExpiryOnExpiryDayItselfShouldStillQualify() {

        List<Instrument> options = chain(NEAR_EXPIRY);

        assertEquals(Optional.of(NEAR_EXPIRY), StrikeWindow.nearestExpiry(options, NEAR_EXPIRY));
    }

    @Test
    void nearestExpiryShouldIgnoreInstrumentsWithNoExpiry() {

        Instrument noExpiry = new Instrument("X", "NSE_FO|X", "NSE_FO", "CE", "NIFTY", 65, 1800, 0.05);

        assertEquals(Optional.empty(), StrikeWindow.nearestExpiry(List.of(noExpiry), LocalDate.of(2026, 8, 15)));
    }

    // ---- select ----

    @Test
    void selectShouldReturnBothCallAndPutAtTheAtmStrike() {

        List<Instrument> result = StrikeWindow.select(chain(NEAR_EXPIRY), NEAR_EXPIRY, 25000, 0);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(i -> i.getStrikePrice() == 25000));
    }

    @Test
    void selectShouldReturnFifteenRungsEachSide() {

        // 61 strikes, 24000 to 27000 step 50 -- spot at the midpoint (25500)
        // has exactly 30 strikes each side, so this exercises the full window
        // without hitting either edge.
        List<Instrument> options = new ArrayList<>();
        for (double strike = 24000; strike <= 27000; strike += 50) {
            options.add(option(strike, "CE", NEAR_EXPIRY));
            options.add(option(strike, "PE", NEAR_EXPIRY));
        }

        List<Instrument> result = StrikeWindow.select(options, NEAR_EXPIRY, 25500, 15);

        // 15 below + ATM + 15 above = 31 distinct strikes, x2 for CE/PE.
        long distinctStrikes = result.stream().map(Instrument::getStrikePrice).distinct().count();
        assertEquals(31, distinctStrikes);
        assertEquals(62, result.size());
        assertEquals(24750.0, result.stream().mapToDouble(Instrument::getStrikePrice).min().orElseThrow());
        assertEquals(26250.0, result.stream().mapToDouble(Instrument::getStrikePrice).max().orElseThrow());
    }

    @Test
    void selectShouldPickTheClosestStrikeWhenSpotFallsBetweenTwo() {

        // Spot exactly between 25000 and 25050 would tie; nudge toward 25050.
        List<Instrument> result = StrikeWindow.select(chain(NEAR_EXPIRY), NEAR_EXPIRY, 25030, 0);

        assertEquals(25050.0, result.get(0).getStrikePrice());
    }

    @Test
    void selectShouldClampAtTheLowEdgeOfTheChain() {

        // Nearest strike to spot=24500 is index 0 (the chain's own low
        // edge), so the window extends only upward -- 16 strikes (itself
        // plus 15 above), not the full 21-strike chain.
        List<Instrument> result = StrikeWindow.select(chain(NEAR_EXPIRY), NEAR_EXPIRY, 24500, 15);

        long distinctStrikes = result.stream().map(Instrument::getStrikePrice).distinct().count();
        assertEquals(16, distinctStrikes);
        assertEquals(24500.0, result.stream().mapToDouble(Instrument::getStrikePrice).min().orElseThrow());
        assertEquals(25250.0, result.stream().mapToDouble(Instrument::getStrikePrice).max().orElseThrow());
    }

    @Test
    void selectShouldClampAtTheHighEdgeOfTheChain() {

        // Symmetric to the low-edge case: nearest strike to spot=25500 is
        // the chain's own high edge, so the window extends only downward.
        List<Instrument> result = StrikeWindow.select(chain(NEAR_EXPIRY), NEAR_EXPIRY, 25500, 15);

        long distinctStrikes = result.stream().map(Instrument::getStrikePrice).distinct().count();
        assertEquals(16, distinctStrikes);
        assertEquals(24750.0, result.stream().mapToDouble(Instrument::getStrikePrice).min().orElseThrow());
        assertEquals(25500.0, result.stream().mapToDouble(Instrument::getStrikePrice).max().orElseThrow());
    }

    @Test
    void selectShouldIgnoreInstrumentsForOtherExpiries() {

        List<Instrument> options = new ArrayList<>();
        options.addAll(chain(NEAR_EXPIRY));
        options.addAll(chain(FAR_EXPIRY));

        List<Instrument> result = StrikeWindow.select(options, NEAR_EXPIRY, 25000, 0);

        assertTrue(result.stream().allMatch(i -> NEAR_EXPIRY.equals(i.getExpiry())));
    }

    @Test
    void selectShouldReturnEmptyWhenNoInstrumentMatchesTheExpiry() {

        assertEquals(List.of(), StrikeWindow.select(chain(NEAR_EXPIRY), FAR_EXPIRY, 25000, 15));
    }

    @Test
    void selectShouldRejectNegativeRungs() {

        assertThrows(IllegalArgumentException.class,
                () -> StrikeWindow.select(chain(NEAR_EXPIRY), NEAR_EXPIRY, 25000, -1));
    }
}
