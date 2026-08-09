package com.kbquants.session;

import java.util.function.Consumer;

/**
 * A source of price ticks for one instrument.
 */
public interface MarketDataFeed {

    void start(PriceListener listener);

    /**
     * Releases whatever the feed holds -- a WebSocket connection, a
     * scheduler thread. Called when the trade it was opened for closes or
     * is released, so a session's worth of trades does not leave a
     * connection open per trade.
     * <p>
     * Default no-op for feeds with nothing to release (a replay over a
     * fixed list, say), and it must be safe to call more than once.
     */
    default void stop() {
    }

    /**
     * Registers a callback for the feed failing or dropping, invoked with a
     * human-readable reason.
     * <p>
     * This exists because a dead feed is otherwise invisible: prices simply
     * stop arriving, the stop-loss quietly stops being enforced, and
     * everything still looks healthy. Silence is the dangerous state, so
     * the failure has to reach the user rather than only the log.
     */
    default void setFailureListener(Consumer<String> onFailure) {
    }
}
