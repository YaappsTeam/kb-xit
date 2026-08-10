package com.kbquants.session;

/**
 * The broker could not be asked what is open.
 * <p>
 * Checked, so it cannot be quietly swallowed into an empty result. An
 * unanswered question and an empty answer mean opposite things here.
 */
public class PositionQueryException extends Exception {

    public PositionQueryException(String message) {
        super(message);
    }

    public PositionQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
