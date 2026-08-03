package com.kbquants.session;

public interface MarketDataFeed {
    void start(PriceListener listener);
}
