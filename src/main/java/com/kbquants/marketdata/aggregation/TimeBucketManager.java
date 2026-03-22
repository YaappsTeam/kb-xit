package com.kbquants.marketdata.aggregation;


import com.kbquants.marketdata.model.Candle;
import com.kbquants.marketdata.model.Timeframe;
import com.kbquants.marketdata.stream.CandleSubscriber;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class TimeBucketManager implements CandleSubscriber {

    // symbol → timeframe → aggregator
    private final Map<String, Map<Timeframe, CandleAggregator>> aggregators = new HashMap<>();

    private final Consumer<Candle> outputListener;

    public TimeBucketManager(Consumer<Candle> outputListener) {
        this.outputListener = outputListener;
    }

    public void register(String symbol, Timeframe timeframe) {

        aggregators.computeIfAbsent(symbol, s -> new HashMap<>())
                .computeIfAbsent(timeframe, tf -> new CandleAggregator(tf, symbol, outputListener));
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