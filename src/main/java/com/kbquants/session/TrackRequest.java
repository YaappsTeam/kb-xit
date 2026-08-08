package com.kbquants.session;

import lombok.Getter;

/**
 * The outcome of turning raw /buy arguments into something tradable:
 * either a fully resolved instrument key, entry price and quantity, or a
 * rejection carrying a message fit to send straight back to the user.
 * <p>
 * Rejection is a value, not an exception, because every failure here is a
 * normal user-facing outcome (unknown symbol, ambiguous symbol, capital
 * below one lot) that must be reported rather than thrown.
 */
@Getter
public final class TrackRequest {

    private final boolean accepted;
    private final String instrumentKey;
    private final String displaySymbol;
    private final double price;
    private final int quantity;
    private final double tickSize;
    private final String note;
    private final String rejectionReason;

    private TrackRequest(boolean accepted, String instrumentKey, String displaySymbol, double price,
                       int quantity, double tickSize, String note, String rejectionReason) {
        this.accepted = accepted;
        this.instrumentKey = instrumentKey;
        this.displaySymbol = displaySymbol;
        this.price = price;
        this.quantity = quantity;
        this.tickSize = tickSize;
        this.note = note;
        this.rejectionReason = rejectionReason;
    }

    public static TrackRequest accepted(String instrumentKey, String displaySymbol, double price, int quantity, String note) {
        return accepted(instrumentKey, displaySymbol, price, quantity, 0, note);
    }

    public static TrackRequest accepted(String instrumentKey, String displaySymbol, double price, int quantity,
                                      double tickSize, String note) {
        return new TrackRequest(true, instrumentKey, displaySymbol, price, quantity, tickSize, note, null);
    }

    public static TrackRequest rejected(String reason) {
        return new TrackRequest(false, null, null, 0, 0, 0, null, reason);
    }
}
