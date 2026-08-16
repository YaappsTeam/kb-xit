package com.kbquants.marketdata.pipeline;


import com.kbquants.marketdata.aggregation.TimeBucketManager;
import com.kbquants.marketdata.model.Candle;
import com.kbquants.marketdata.model.Timeframe;
import com.kbquants.marketdata.stream.CandleStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class MarketDataPipeline {

    private static final Logger log = LoggerFactory.getLogger(MarketDataPipeline.class);

    private final CandleStream stream;
    private final TimeBucketManager manager;

    public MarketDataPipeline(Consumer<Candle> finalConsumer) {

        this.stream = new CandleStream();
        this.manager = new TimeBucketManager(finalConsumer);

        // connect stream → manager
        stream.subscribe(manager);
    }

    public void register(String symbol, Timeframe timeframe) {
        log.debug("Registering pipeline aggregation symbol={} timeframe={}", symbol, timeframe);
        manager.register(symbol, timeframe);
    }

    public void onCandle(Candle candle) {
        stream.publish(candle);
    }
}