package com.kbquants.notification;

/**
 * Callback for commands received via the Telegram bot's two-way control
 * plane. entries are user invocations (not decisions the app makes) --
 * see PRODUCT_REQUIREMENTS.md section 2.
 */
public interface TelegramCommandListener {

    /**
     * Raw whitespace-separated arguments following "/track", left
     * uninterpreted on purpose: whether a trailing number is a quantity or
     * part of a multi-word symbol can only be decided against the
     * instrument master, which lives well below this interface.
     */
    void onTrack(java.util.List<String> args);

    void onExit(String orderId);
    void onExitAll();
    void onStatusRequested();

    /**
     * Re-fetch the instrument master now, rather than waiting for its
     * weekly refresh -- for picking up a contract listed since the last one.
     */
    void onRefreshInstruments();

    /** Offer the available milestone sets for the user to choose between. */
    void onLadderChoicesRequested();

    /** Make the named milestone set the active one for subsequent trades. */
    void onLadderSelected(String setName);

    /**
     * Stop or resume taking on new trades. Affects future invocations only
     * -- trades already open keep being managed, which is why this is
     * separate from releasing them.
     */
    void onAdoptionPaused(boolean paused);

    /**
     * Change how far the engine may act on a trade, without closing it.
     *
     * @param target  an orderId, or "all"
     */
    void onMonitorModeRequested(String target, com.kbquants.domain.MonitorMode mode);

    /**
     * Something command-shaped that matched nothing. Answered rather than
     * ignored, so a typo cannot leave the user believing a trade is being
     * watched when no command was dispatched.
     */
    void onUnknownCommand(String command);

    /** List what the bot accepts. */
    void onHelpRequested();

    /**
     * A recognised command with the wrong arguments. Answered for the same
     * reason unknown commands are: failing silently leaves the user
     * expecting a trade to be tracked when nothing was.
     */
    void onMalformedCommand(String command, String usage);

    /** Report the current per-trade risk ceiling. */
    void onRiskShow();

    /**
     * Change the rupee loss a trade may take at its hard stop, applying to
     * trades opened afterwards. Zero removes the ceiling.
     */
    void onRiskSet(double maxRiskPerTrade);

    /**
     * Offer the open trades as buttons to close. Orders are identified by
     * generated UUIDs, which nobody should have to retype while a position
     * is moving.
     */
    void onExitChoicesRequested();

    /** Offer the open trades as buttons for the given monitoring mode. */
    void onMonitorModeChoicesRequested(com.kbquants.domain.MonitorMode mode);

    /** Report whether a usable trading token is held, without revealing it. */
    void onTokenStatusRequested();

    /**
     * Supply the daily trading token. The value is a live credential, so
     * implementations must not log or echo it.
     */
    void onTokenProvided(String token);

    /** Take on a detected broker position. */
    void onAdoptRequested(String orderId);

    /** Decline a detected broker position, permanently. */
    void onIgnoreRequested(String orderId);

    /** List positions detected at the broker but not managed. */
    void onPendingAdoptionsRequested();

    /**
     * Check what is being managed against what the broker actually holds.
     * A position closed by hand leaves this system watching something that
     * is not there.
     */
    void onReconcileRequested();

    /**
     * This listener's own account id, for log correlation (see
     * CODING_STANDARDS.md). {@link TelegramCommandHandler} puts it in MDC
     * around dispatch, so every log line a command produces is filterable
     * by account without each of the methods above setting it themselves.
     */
    String accountId();
}
