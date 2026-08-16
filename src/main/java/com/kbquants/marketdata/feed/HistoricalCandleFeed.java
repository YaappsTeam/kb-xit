package com.kbquants.marketdata.feed;


import com.kbquants.marketdata.model.Candle;
import com.kbquants.marketdata.model.Timeframe;

import java.time.LocalDate;
import java.util.List;

public interface HistoricalCandleFeed {

    // HISTORICAL
    List<Candle> getHistoricalCandles(String symbol, Timeframe timeframe, LocalDate from, LocalDate to);

    // LIVE STREAM
//    void subscribe(
//            String symbol,
//            Timeframe timeframe,
//            CandleListener listener
//    );

    void unsubscribe(String symbol);

}