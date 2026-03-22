package com.kbquants.marketdata.broker.upstox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbquants.marketdata.feed.MarketDataFeed;
import com.kbquants.marketdata.model.Candle;
import com.kbquants.marketdata.model.Timeframe;

import java.time.LocalDate;
import java.util.List;

public class UpstoxMarketDataFeed implements MarketDataFeed {

    private final UpstoxHistoricalClient client;
    private final UpstoxCandleParser parser;
    private final ObjectMapper mapper;

    public UpstoxMarketDataFeed(String token, ObjectMapper mapper) {
        this.client = new UpstoxHistoricalClient(token);
        this.parser = new UpstoxCandleParser();
        this.mapper = mapper;
    }

    @Override
    public List<Candle> getHistoricalCandles(String symbol, Timeframe timeframe, LocalDate from, LocalDate to) {

        try {
            String raw = client.fetch(symbol, mapTimeframe(timeframe), from, to);
            UpstoxCandleResponse response = mapper.readValue(raw, UpstoxCandleResponse.class);

            return parser.parse(response, symbol, timeframe);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

//    @Override
//    public void subscribe(String symbol,
//                          Timeframe timeframe,
//                          CandleListener listener) {
//
//        // TODO: WebSocket implementation (Phase 2)
//    }

    @Override
    public void unsubscribe(String symbol) {
        // TODO
    }

    private String mapTimeframe(Timeframe tf) {
        switch (tf) {
            case ONE_MIN:
                return "1minute";
            case FIVE_MIN:
                return "5minute";
            case FIFTEEN_MIN:
                return "15minute";
            default:
                throw new IllegalArgumentException("Unsupported TF");
        }
    }
}