package com.kbquants.marketdata.pricepath;

import com.kbquants.instrument.Instrument;
import com.kbquants.instrument.InstrumentCatalog;
import com.kbquants.instrument.InstrumentRegistry;
import com.kbquants.marketdata.feed.HistoricalCandleFeed;
import com.kbquants.marketdata.model.Candle;
import com.kbquants.marketdata.model.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The historical price-path source is only ever meant to backtest against
 * this deployment's NIFTY-options scope -- same reasoning as
 * TradeMonitorAdoptionTest's scope-enforcement tests for live fills.
 */
class HistoricalPricePathSourceTest {

    private static final class FakeCandleFeed implements HistoricalCandleFeed {
        final List<Candle> candles;
        String lastRequestedInstrumentKey;

        FakeCandleFeed(List<Candle> candles) {
            this.candles = candles;
        }

        @Override
        public List<Candle> getHistoricalCandles(String symbol, Timeframe timeframe, LocalDate from, LocalDate to) {
            this.lastRequestedInstrumentKey = symbol;
            return candles;
        }

        @Override
        public void unsubscribe(String symbol) {
        }
    }

    private static Candle candle(double close) {
        return Candle.builder().timestamp(0L).open(close).high(close).low(close).close(close)
                .volume(0L).symbol("NSE_FO|IN_SCOPE").timeframe(Timeframe.ONE_MIN).build();
    }

    /** A catalog whose only known instrument is the given key -- everything else is out of scope. */
    private static InstrumentCatalog catalogKnowingOnly(String instrumentKey) {
        Instrument known = new Instrument(instrumentKey, instrumentKey, "NSE_FO", "CE", "NIFTY", 65, 1800, 0.05);
        return new InstrumentCatalog(new InstrumentRegistry(List.of(known)));
    }

    @Test
    void fetchesAndConvertsCandlesForAnInScopeInstrument() {

        FakeCandleFeed feed = new FakeCandleFeed(List.of(candle(100.0), candle(105.0)));
        HistoricalPricePathSource source = new HistoricalPricePathSource(
                feed, Optional.of(catalogKnowingOnly("NSE_FO|IN_SCOPE")));

        List<Double> path = source.fetch("NSE_FO|IN_SCOPE", Timeframe.ONE_MIN,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5));

        assertEquals(List.of(100.0, 105.0), path);
        assertEquals("NSE_FO|IN_SCOPE", feed.lastRequestedInstrumentKey);
    }

    @Test
    void refusesAnInstrumentKeyOutsideTheCatalogsScope() {

        FakeCandleFeed feed = new FakeCandleFeed(List.of(candle(100.0)));
        HistoricalPricePathSource source = new HistoricalPricePathSource(
                feed, Optional.of(catalogKnowingOnly("NSE_FO|IN_SCOPE")));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> source.fetch("NSE_EQ|OUT_OF_SCOPE", Timeframe.ONE_MIN,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5)));

        assertTrue(exception.getMessage().contains("NSE_EQ|OUT_OF_SCOPE"));
    }

    @Test
    void withNoCatalogEveryInstrumentIsInScope() {

        FakeCandleFeed feed = new FakeCandleFeed(List.of(candle(42.0)));
        HistoricalPricePathSource source = new HistoricalPricePathSource(feed, Optional.empty());

        List<Double> path = source.fetch("NSE_EQ|ANYTHING", Timeframe.ONE_MIN,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5));

        assertEquals(List.of(42.0), path);
    }
}
