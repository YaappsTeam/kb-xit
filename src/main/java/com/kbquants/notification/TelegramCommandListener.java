package com.kbquants.notification;

/**
 * Callback for commands received via the Telegram bot's two-way control
 * plane. entries are user invocations (not decisions the app makes) --
 * see PRODUCT_REQUIREMENTS.md section 2.
 */
public interface TelegramCommandListener {

    /**
     * Raw whitespace-separated arguments following "/buy", left
     * uninterpreted on purpose: whether a trailing number is a quantity or
     * part of a multi-word symbol can only be decided against the
     * instrument master, which lives well below this interface.
     */
    void onBuy(java.util.List<String> args);

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
}
