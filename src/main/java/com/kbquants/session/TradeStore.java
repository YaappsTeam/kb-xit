package com.kbquants.session;

import java.util.List;

/**
 * Where open trades are kept so they survive the process dying.
 * <p>
 * This exists because the position does not disappear when the process
 * does. Without it, a crash or a restart loses the entry, the
 * cost-inclusive breakeven, the phase and the ratcheted stop, while the
 * position sits open at the broker with nothing watching it -- the exact
 * situation the system is meant to prevent.
 */
public interface TradeStore {

    /** Replaces the stored set. Failures are logged, never thrown: losing persistence must not interrupt trading. */
    void save(List<TradeSnapshot> trades);

    /** Trades from a previous run, or empty if there are none or the store cannot be read. */
    List<TradeSnapshot> load();
}
