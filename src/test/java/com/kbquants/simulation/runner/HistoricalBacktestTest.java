package com.kbquants.simulation.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbquants.domain.OwnershipMode;
import com.kbquants.instrument.Instrument;
import com.kbquants.instrument.InstrumentCatalog;
import com.kbquants.instrument.InstrumentRegistry;
import com.kbquants.marketdata.broker.upstox.UpstoxCandleParser;
import com.kbquants.marketdata.broker.upstox.UpstoxCandleResponse;
import com.kbquants.marketdata.feed.HistoricalCandleFeed;
import com.kbquants.marketdata.model.Candle;
import com.kbquants.marketdata.model.Timeframe;
import com.kbquants.marketdata.pricepath.HistoricalPricePathSource;
import com.kbquants.simulation.MarketRegime;
import com.kbquants.simulation.ScenarioConfig;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Proves the story #24 pieces actually compose: a real (fixture-derived)
 * historical price path, scoped to a NIFTY-options instrument, run through
 * the existing simulation executor completely unmodified. No changes were
 * needed to SequentialCombinationExecutor, PricePathGenerator, or
 * ScenarioConfig -- the historical path is just another List&lt;Double&gt;,
 * the same shape StochasticPricePathGenerator already produces.
 */
class HistoricalBacktestTest {

    private static final String NIFTY_OPTION_KEY = "NSE_FO|NIFTY25AUG24000CE";

    /** Stands in for UpstoxHistoricalCandleFeed, sourcing candles from canned Upstox JSON. */
    private static final class FixtureCandleFeed implements HistoricalCandleFeed {

        private final ObjectMapper objectMapper = new ObjectMapper();
        private final UpstoxCandleParser parser = new UpstoxCandleParser();

        @Override
        public List<Candle> getHistoricalCandles(String symbol, Timeframe timeframe, LocalDate from, LocalDate to) {
            try {
                String json = """
                        {
                          "status": "success",
                          "data": {
                            "candles": [
                              ["2026-01-05T15:27:00+05:30", 100.0, 111.0, 99.0,  110.0, 4600, 1150],
                              ["2026-01-05T15:28:00+05:30", 110.0, 121.0, 109.0, 120.0, 4800, 1180],
                              ["2026-01-05T15:29:00+05:30", 120.0, 132.0, 118.0, 130.0, 5000, 1200]
                            ]
                          }
                        }
                        """;
                UpstoxCandleResponse response = objectMapper.readValue(json, UpstoxCandleResponse.class);
                return parser.parse(response, symbol, timeframe);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void unsubscribe(String symbol) {
        }
    }

    @Test
    void aHistoricalPricePathRunsThroughTheExistingExecutorUnmodified() {

        Instrument niftyOption = new Instrument(NIFTY_OPTION_KEY, NIFTY_OPTION_KEY, "NSE_FO", "CE", "NIFTY", 65, 24000, 0.05);
        InstrumentCatalog catalog = new InstrumentCatalog(new InstrumentRegistry(List.of(niftyOption)));
        HistoricalPricePathSource source = new HistoricalPricePathSource(new FixtureCandleFeed(), Optional.of(catalog));

        List<Double> historicalPricePath = source.fetch(NIFTY_OPTION_KEY, Timeframe.ONE_MIN,
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5));

        assertEquals(List.of(110.0, 120.0, 130.0), historicalPricePath,
                "the parser preserves the fixture's chronological (oldest-first) candle order");

        SimulationRequest request = new SimulationRequest(
                MarketRegime.RANDOM,
                new ScenarioConfig(0, 0.0, 0.0, 0.0, 1L),
                List.of(OwnershipMode.CONTINUOUS, OwnershipMode.MILESTONE),
                ExecutionMode.SEQUENTIAL);

        SimulationResult result = new SequentialCombinationExecutor().execute(historicalPricePath, request);

        assertEquals(2, result.getTradeMetricsList().size());
        for (TradeMetrics metrics : result.getTradeMetricsList()) {
            assertFalse(metrics.isForceExited(), "an uninterrupted rally shouldn't force-exit either ownership mode");
        }
    }
}
