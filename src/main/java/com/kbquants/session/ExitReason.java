package com.kbquants.session;

/** Why the engine is trying to close a position. */
public enum ExitReason {

    STOP_LOSS("stop-loss"),
    MANUAL("manual /exit"),
    END_OF_DAY("end-of-day cutoff");

    private final String description;

    ExitReason(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return description;
    }
}
