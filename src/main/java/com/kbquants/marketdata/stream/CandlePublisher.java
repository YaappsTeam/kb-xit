package com.kbquants.marketdata.stream;

public interface CandlePublisher {

    void subscribe(CandleSubscriber subscriber);

    void unsubscribe(CandleSubscriber subscriber);

}