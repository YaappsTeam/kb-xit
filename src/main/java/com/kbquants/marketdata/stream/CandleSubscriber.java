package com.kbquants.marketdata.stream;


import com.kbquants.marketdata.model.Candle;

public interface CandleSubscriber {

    void onCandle(Candle candle);

}