package com.kbquants.marketdata.broker.upstox;


import com.kbquants.marketdata.model.Candle;
import com.kbquants.marketdata.model.Timeframe;
import com.kbquants.marketdata.parser.CandleParser;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class UpstoxCandleParser implements CandleParser<UpstoxCandleResponse> {

    @Override
    public List<Candle> parse(
            UpstoxCandleResponse response,
            String symbol,
            Timeframe timeframe) {

        return response.getData()
                .getCandles()
                .stream()
                .map(c -> Candle.builder()
                        .timestamp(parseTime((String) c.get(0)))
                        .open(toDouble(c.get(1)))
                        .high(toDouble(c.get(2)))
                        .low(toDouble(c.get(3)))
                        .close(toDouble(c.get(4)))
                        .volume(toLong(c.get(5)))
                        .openInterest(toNullableLong(c.get(6)))
                        .symbol(symbol)
                        .timeframe(timeframe)
                        .build())
                .collect(Collectors.toList());
    }

    private long parseTime(String ts) {
        return OffsetDateTime.parse(ts)
                .toInstant()
                .toEpochMilli();
    }

    private double toDouble(Object o) {
        return ((Number) o).doubleValue();
    }

    private long toLong(Object o) {
        return ((Number) o).longValue();
    }

    private Long toNullableLong(Object o) {
        if (o == null) return null;
        return ((Number) o).longValue();
    }
}