package com.kbquants.domain;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The daily Upstox access token used for placing orders.
 * <p>
 * Separate from the year-long Analytics Token that market data uses: this
 * one expires at 3:30 AM the next day and can only be obtained through an
 * interactive browser login with 2FA, which cannot be automated. So it is
 * supplied at runtime rather than configured, and the system has to cope
 * with simply not having one.
 * <p>
 * Never logged, never persisted, never echoed back. It authorises real
 * orders on a real account, and the one place it is most likely to leak is
 * the diagnostics written while working out why something failed.
 */
public final class TradingToken {

    /** Upstox tokens die at 3:30 AM regardless of when they were issued. */
    private static final LocalTime DAILY_EXPIRY = LocalTime.of(3, 30);

    private final ZoneId zone;

    private volatile String value;
    private volatile ZonedDateTime acquiredAt;

    public TradingToken(ZoneId zone) {
        this.zone = zone;
    }

    public void set(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        this.value = token.trim();
        this.acquiredAt = ZonedDateTime.now(zone);
    }

    public void clear() {
        this.value = null;
        this.acquiredAt = null;
    }

    public boolean isPresent() {
        return value != null;
    }

    /**
     * Usable only if it exists and the 3:30 AM boundary has not passed
     * since it was supplied.
     */
    public boolean isUsable() {
        return isUsableAt(ZonedDateTime.now(zone));
    }

    boolean isUsableAt(ZonedDateTime now) {
        if (value == null || acquiredAt == null) {
            return false;
        }
        return !now.isAfter(expiresAt(acquiredAt));
    }

    /**
     * The first 3:30 AM strictly after the token was supplied. A token
     * issued at 09:00 dies the following morning; one supplied at 02:00
     * dies at 03:30 the same day, which is the case a naive
     * "acquired-plus-a-day" rule gets wrong.
     */
    static ZonedDateTime expiresAt(ZonedDateTime acquiredAt) {
        ZonedDateTime candidate = acquiredAt.with(DAILY_EXPIRY);
        return candidate.isAfter(acquiredAt) ? candidate : candidate.plusDays(1);
    }

    /**
     * The token itself, or null. Callers must not log or report the
     * returned value.
     */
    public String value() {
        return value;
    }

    /** Safe to show: describes the token without revealing it. */
    public String describe() {
        if (value == null) {
            return "no trading token — orders cannot be placed. Send /token <value> after logging in to Upstox.";
        }
        if (!isUsable()) {
            return "trading token EXPIRED (supplied " + acquiredAt.toLocalDateTime()
                    + "; they die at 3:30 AM). Orders cannot be placed until you send a fresh /token.";
        }
        return String.format("trading token active, supplied %s, valid until %s",
                acquiredAt.toLocalTime().withNano(0), expiresAt(acquiredAt).toLocalDateTime());
    }
}
