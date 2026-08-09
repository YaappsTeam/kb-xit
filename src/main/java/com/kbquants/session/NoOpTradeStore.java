package com.kbquants.session;

import java.util.List;

/**
 * Keeps nothing. For tests, and for runs where trades are inherently
 * throwaway.
 */
public final class NoOpTradeStore implements TradeStore {

    @Override
    public void save(List<TradeSnapshot> trades) {
    }

    @Override
    public List<TradeSnapshot> load() {
        return List.of();
    }
}
