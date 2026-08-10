package com.kbquants.domain;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daily order-placement token.
 * <p>
 * Upstox tokens die at 3:30 AM regardless of when they were issued, which
 * a naive "valid for 24 hours" rule gets wrong at both ends.
 */
class TradingTokenTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static ZonedDateTime at(int hour, int minute) {
        return ZonedDateTime.of(2026, 8, 10, hour, minute, 0, 0, IST);
    }

    @Test
    void shouldStartWithNoToken() {

        TradingToken token = new TradingToken(IST);

        assertFalse(token.isPresent());
        assertFalse(token.isUsable());
    }

    @Test
    void shouldHoldASuppliedToken() {

        TradingToken token = new TradingToken(IST);
        token.set("abc123");

        assertTrue(token.isPresent());
        assertTrue(token.isUsable());
        assertEquals("abc123", token.value());
    }

    @Test
    void shouldTrimAndRejectBlankTokens() {

        TradingToken token = new TradingToken(IST);
        token.set("  abc123  ");
        assertEquals("abc123", token.value());

        assertThrows(IllegalArgumentException.class, () -> token.set("   "));
        assertThrows(IllegalArgumentException.class, () -> token.set(null));
    }

    /** A token taken during the trading day dies the following morning. */
    @Test
    void aTokenSuppliedDuringTheDayShouldExpireTheNextMorning() {

        ZonedDateTime expiry = TradingToken.expiresAt(at(9, 0));

        assertEquals(11, expiry.getDayOfMonth());
        assertEquals(3, expiry.getHour());
        assertEquals(30, expiry.getMinute());
    }

    /**
     * The case a "plus 24 hours" rule gets wrong: one supplied at 02:00
     * has only ninety minutes left, not a day.
     */
    @Test
    void aTokenSuppliedBeforeDawnShouldExpireTheSameMorning() {

        ZonedDateTime expiry = TradingToken.expiresAt(at(2, 0));

        assertEquals(10, expiry.getDayOfMonth());
        assertEquals(3, expiry.getHour());
    }

    @Test
    void shouldBeUnusableOnceTheBoundaryHasPassed() {

        TradingToken token = new TradingToken(IST);
        token.set("abc123");

        assertTrue(token.isUsableAt(ZonedDateTime.now(IST)));
        assertFalse(token.isUsableAt(ZonedDateTime.now(IST).plusDays(2)));
    }

    @Test
    void clearingShouldRemoveIt() {

        TradingToken token = new TradingToken(IST);
        token.set("abc123");
        token.clear();

        assertFalse(token.isPresent());
        assertFalse(token.isUsable());
    }

    /**
     * describe() is shown in chat, so it must never contain the credential
     * -- this is the likeliest accidental leak.
     */
    @Test
    void descriptionShouldNeverRevealTheToken() {

        TradingToken token = new TradingToken(IST);
        token.set("super-secret-value-12345");

        assertFalse(token.describe().contains("super-secret-value-12345"),
                () -> "token leaked into: " + token.describe());
    }

    @Test
    void descriptionShouldSayWhenThereIsNoToken() {

        assertTrue(new TradingToken(IST).describe().contains("no trading token"));
    }
}
