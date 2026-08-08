package com.kbquants.domain;

/**
 * How far the exit engine is allowed to act on a trade.
 * <p>
 * Exists because "stop managing this" and "close this" are different
 * intentions, and only one of them was expressible before: /exit sells the
 * position. Once real sell orders are placed that distinction is the
 * difference between handing a trade back and liquidating it.
 */
public enum MonitorMode {

    /** Full management: notifications, ratcheting stop, automatic exit. */
    MANAGED,

    /**
     * Notifications only. The engine keeps tracking phases and the stop so
     * the numbers stay meaningful, but never exits -- when the stop is
     * breached it says so and leaves the decision alone. This is what you
     * usually want when taking manual control: the information remains
     * useful, the trigger is yours.
     */
    OBSERVED,

    /**
     * Detached. No notifications, no engine, no exit. The position is
     * entirely the user's again.
     */
    RELEASED;

    public boolean isAutoExitAllowed() {
        return this == MANAGED;
    }

    public boolean isNotifying() {
        return this != RELEASED;
    }
}
