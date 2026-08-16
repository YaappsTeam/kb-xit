package com.kbquants.marketdata.aggregation;


import com.kbquants.marketdata.model.Candle;
import com.kbquants.marketdata.model.Timeframe;
import com.kbquants.marketdata.stream.CandleSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class TimeBucketManager implements CandleSubscriber {

    private static final Logger log = LoggerFactory.getLogger(TimeBucketManager.class);

    // symbol → timeframe → aggregator
    private final Map<String, Map<Timeframe, CandleAggregator>> aggregators = new HashMap<>();

    private final Consumer<Candle> outputListener;

    public TimeBucketManager(Consumer<Candle> outputListener) {
        this.outputListener = outputListener;
    }

    public void register(String symbol, Timeframe timeframe) {

        aggregators.computeIfAbsent(symbol, s -> new HashMap<>())
                .computeIfAbsent(timeframe, tf -> new CandleAggregator(tf, symbol, outputListener));
        log.info("Registered aggregator symbol={} timeframe={}", symbol, timeframe);
    }

    @Override
    public void onCandle(Candle candle) {
        this.onCandleInternal(candle);
    }

    private void onCandleInternal(Candle candle) {
        Map<Timeframe, CandleAggregator> tfMap = aggregators.get(candle.getSymbol());

        if (tfMap == null) return;

        for (CandleAggregator agg : tfMap.values()) {
            agg.onCandle(candle);
        }
    }
}