package com.kbquants.marketdata.aggregation;

import com.kbquants.marketdata.model.Candle;
import com.kbquants.marketdata.model.Timeframe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandleAggregatorTest {

    private static Candle candle(long timestamp, double open, double high, double low, double close, long volume) {
        return Candle.builder()
                .timestamp(timestamp)
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .volume(volume)
                .symbol("TEST")
                .timeframe(Timeframe.ONE_MIN)
                .build();
    }

    @Test
    void onCandle_withinSameBucket_doesNotEmit() {
        List<Candle> emitted = new ArrayList<>();
        CandleAggregator aggregator = new CandleAggregator(Timeframe.FIVE_MIN, "TEST", emitted::add);

        // Both timestamps fall inside the same 5-minute bucket starting at 0.
        aggregator.onCandle(candle(0L, 100, 105, 99, 104, 10));
        aggregator.onCandle(candle(60_000L, 104, 108, 103, 107, 5));

        assertTrue(emitted.isEmpty(), "no candle should be emitted while inside the same bucket");
    }

    @Test
    void onCandle_crossingBucketBoundary_emitsAggregatedCandle() {
        List<Candle> emitted = new ArrayList<>();
        CandleAggregator aggregator = new CandleAggregator(Timeframe.FIVE_MIN, "TEST", emitted::add);

        aggregator.onCandle(candle(0L, 100, 105, 99, 104, 10));
        aggregator.onCandle(candle(60_000L, 104, 108, 103, 107, 5));
        // This timestamp (5 min) starts a new bucket → emits the first aggregated candle.
        aggregator.onCandle(candle(300_000L, 107, 109, 106, 108, 7));

        assertEquals(1, emitted.size());
        Candle result = emitted.get(0);
        assertEquals(0L, result.getTimestamp(), "emitted candle carries the bucket start timestamp");
        assertEquals(100.0, result.getOpen());
        assertEquals(108.0, result.getHigh());
        assertEquals(99.0, result.getLow());
        assertEquals(107.0, result.getClose());
        assertEquals(15L, result.getVolume());
        assertEquals(Timeframe.FIVE_MIN, result.getTimeframe());
        assertEquals("TEST", result.getSymbol());
    }

    @Test
    void onCandle_multipleBucketCrossings_emitsOneCandlePerCompletedBucket() {
        List<Candle> emitted = new ArrayList<>();
        CandleAggregator aggregator = new CandleAggregator(Timeframe.ONE_MIN, "TEST", emitted::add);

        aggregator.onCandle(candle(0L, 100, 101, 99, 100, 1));
        aggregator.onCandle(candle(60_000L, 100, 102, 98, 101, 1));
        aggregator.onCandle(candle(120_000L, 101, 103, 97, 102, 1));

        // Two completed buckets (0-1min, 1-2min); the 2-3min bucket is still open.
        assertEquals(2, emitted.size());
        assertEquals(0L, emitted.get(0).getTimestamp());
        assertEquals(60_000L, emitted.get(1).getTimestamp());
    }
}
