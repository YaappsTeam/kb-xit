package com.kbquants.marketdata.pipeline;


import com.kbquants.marketdata.aggregation.TimeBucketManager;
import com.kbquants.marketdata.model.Candle;
import com.kbquants.marketdata.model.Timeframe;
import com.kbquants.marketdata.stream.CandleStream;

import java.util.function.Consumer;

public class MarketDataPipeline {

    private final CandleStream stream;
    private final TimeBucketManager manager;

    public MarketDataPipeline(Consumer<Candle> finalConsumer) {

        this.stream = new CandleStream();
        this.manager = new TimeBucketManager(finalConsumer);

        // connect stream → manager
        stream.subscribe(manager);
    }

    public void register(String symbol, Timeframe tf) {
        manager.register(symbol, tf);
    }

    public void onCandle(Candle candle) {
        stream.publish(candle);
    }
}