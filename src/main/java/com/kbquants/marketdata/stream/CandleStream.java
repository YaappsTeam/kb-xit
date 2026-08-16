package com.kbquants.marketdata.stream;


import com.kbquants.marketdata.model.Candle;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class CandleStream implements CandlePublisher {

    private final List<CandleSubscriber> subscribers = new CopyOnWriteArrayList<>();

    @Override
    public void subscribe(CandleSubscriber subscriber) {
        subscribers.add(subscriber);
    }

    @Override
    public void unsubscribe(CandleSubscriber subscriber) {
        subscribers.remove(subscriber);
    }

    public void publish(Candle candle) {

        for (CandleSubscriber s : subscribers) {
            s.onCandle(candle);
        }
    }
}