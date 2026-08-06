package com.kbquants.session;

public interface OrderFillFeed {
    void start(TradeFillListener listener);
    void stop();
}
