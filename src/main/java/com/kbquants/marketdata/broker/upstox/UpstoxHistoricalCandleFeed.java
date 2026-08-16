package com.kbquants.marketdata.broker.upstox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbquants.marketdata.config.MarketDataConfig;
import com.kbquants.marketdata.feed.HistoricalCandleFeed;
import com.kbquants.marketdata.model.Candle;
import com.kbquants.marketdata.model.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;

/**
 * Upstox implementation of {@link HistoricalCandleFeed}. Currently supports
 * historical candle retrieval; live streaming is a later phase.
 */
public class UpstoxHistoricalCandleFeed implements HistoricalCandleFeed {

    private static final Logger log = LoggerFactory.getLogger(UpstoxHistoricalCandleFeed.class);

    private final UpstoxHistoricalClient client;
    private final UpstoxCandleParser parser;
    private final ObjectMapper objectMapper;

    public UpstoxHistoricalCandleFeed(MarketDataConfig config, ObjectMapper objectMapper) {
        this.client = new UpstoxHistoricalClient(config);
        this.parser = new UpstoxCandleParser();
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Candle> getHistoricalCandles(String symbol, Timeframe timeframe, LocalDate from, LocalDate to) {

        try {
            String interval = UpstoxIntervalMapping.toInterval(timeframe);
            String rawResponse = client.fetchHistoricalCandles(symbol, interval, from, to);
            UpstoxCandleResponse response = objectMapper.readValue(rawResponse, UpstoxCandleResponse.class);

            List<Candle> candles = parser.parse(response, symbol, timeframe);
            log.info("Fetched historical candles symbol={} timeframe={} count={}", symbol, timeframe, candles.size());
            return candles;

        } catch (Exception exception) {
            log.error("Failed to fetch historical candles symbol={} timeframe={}", symbol, timeframe, exception);
            throw new IllegalStateException("Failed to fetch historical candles for symbol=" + symbol, exception);
        }
    }

    @Override
    public void unsubscribe(String symbol) {
        // Live subscription is not yet implemented; nothing to unsubscribe.
        log.debug("unsubscribe called for symbol={} (live feed not yet implemented)", symbol);
    }
}
