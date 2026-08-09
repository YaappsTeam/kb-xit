package com.kbquants.session;

public interface OrderFillFeed {
    void start(TradeFillListener listener);
    void stop();

    /**
     * A credential has become available. Feeds needing one that only
     * arrives mid-session -- the daily order token, say -- can connect
     * here rather than at startup, where there is nothing to connect with.
     */
    default void onCredentialAvailable() {
    }
}
