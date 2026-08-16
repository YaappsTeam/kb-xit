package com.kbquants.instrument;

import com.google.gson.stream.JsonReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for InstrumentMasterLoader: the daily cache-file naming, and
 * the parse-time filter that keeps only NIFTY index options. The download
 * itself is not exercised here (needs network access, blocked in this
 * sandbox -- see DEVELOPMENT.md).
 */
class InstrumentMasterLoaderTest {

    private static final Path CACHE_DIR = Paths.get("/tmp/xit-mc-test-instruments");

    // ---- daily cache naming ----

    @Test
    void cacheFileShouldBeStampedWithTheGivenDay() {

        InstrumentMasterLoader loader = new InstrumentMasterLoader("unused", CACHE_DIR);

        Path file = loader.cacheFile(LocalDate.of(2026, 8, 15));

        assertEquals(CACHE_DIR.resolve("NSE-2026-08-15.json.gz"), file);
    }

    @Test
    void cacheFileShouldDifferBetweenConsecutiveDays() {

        InstrumentMasterLoader loader = new InstrumentMasterLoader("unused", CACHE_DIR);

        Path today = loader.cacheFile(LocalDate.of(2026, 8, 15));
        Path tomorrow = loader.cacheFile(LocalDate.of(2026, 8, 16));

        assertFalse(today.equals(tomorrow));
    }

    // ---- parse-time NIFTY-options-only filter ----

    private static String niftyOptionJson(String tradingSymbol, String instrumentKey, String instrumentType,
                                          double strike, long expiryMillis) {
        return String.format("""
                {
                  "segment": "NSE_FO",
                  "name": "NIFTY",
                  "instrument_type": "%s",
                  "instrument_key": "%s",
                  "trading_symbol": "%s",
                  "lot_size": 65,
                  "freeze_quantity": 1800,
                  "tick_size": 0.05,
                  "strike_price": %s,
                  "expiry": %d
                }""", instrumentType, instrumentKey, tradingSymbol, strike, expiryMillis);
    }

    private static List<Instrument> parse(String json) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            return InstrumentMasterLoader.parse(reader);
        }
    }

    @Test
    void shouldKeepANiftyCallOption() throws IOException {

        String json = "[" + niftyOptionJson("NIFTY 25000 CE 18 AUG 26", "NSE_FO|1", "CE", 25000, 1755504000000L) + "]";

        List<Instrument> instruments = parse(json);

        assertEquals(1, instruments.size());
        Instrument instrument = instruments.get(0);
        assertEquals("NIFTY 25000 CE 18 AUG 26", instrument.getTradingSymbol());
        assertEquals(25000.0, instrument.getStrikePrice());
        assertEquals(LocalDate.of(2025, 8, 18), instrument.getExpiry());
        assertTrue(instrument.isOption());
    }

    @Test
    void shouldKeepANiftyPutOption() throws IOException {

        String json = "[" + niftyOptionJson("NIFTY 25000 PE 18 AUG 26", "NSE_FO|2", "PE", 25000, 1755504000000L) + "]";

        assertEquals(1, parse(json).size());
    }

    @Test
    void shouldDropNiftyFutures() throws IOException {

        String json = "[" + niftyOptionJson("NIFTY FUT 28 AUG 26", "NSE_FO|3", "FUT", 0, 1756310400000L) + "]";

        assertTrue(parse(json).isEmpty());
    }

    @Test
    void shouldDropOtherUnderlyingsOptions() throws IOException {

        String json = "[" + niftyOptionJson("BANKNIFTY 55000 CE 18 AUG 26", "NSE_FO|4", "CE", 55000, 1755504000000L)
                .replace("\"name\": \"NIFTY\"", "\"name\": \"BANKNIFTY\"") + "]";

        assertTrue(parse(json).isEmpty());
    }

    @Test
    void shouldDropEquities() throws IOException {

        String json = """
                [{
                  "segment": "NSE_EQ",
                  "name": "ACC",
                  "instrument_type": "EQ",
                  "instrument_key": "NSE_EQ|INE012A01025",
                  "trading_symbol": "ACC",
                  "lot_size": 1,
                  "freeze_quantity": 0,
                  "tick_size": 0.05
                }]""";

        assertTrue(parse(json).isEmpty());
    }

    @Test
    void shouldDropTheIndexItself() throws IOException {

        String json = """
                [{
                  "segment": "NSE_INDEX",
                  "name": "Nifty 50",
                  "instrument_type": null,
                  "instrument_key": "NSE_INDEX|Nifty 50",
                  "trading_symbol": "NIFTY 50",
                  "lot_size": 0,
                  "freeze_quantity": 0,
                  "tick_size": 0.05
                }]""";

        assertTrue(parse(json).isEmpty());
    }

    @Test
    void matchingUnderlyingShouldBeCaseInsensitive() throws IOException {

        String json = "[" + niftyOptionJson("NIFTY 25000 CE 18 AUG 26", "NSE_FO|1", "CE", 25000, 1755504000000L)
                .replace("\"name\": \"NIFTY\"", "\"name\": \"nifty\"") + "]";

        assertEquals(1, parse(json).size());
    }

    @Test
    void shouldTolerateAMissingExpiry() throws IOException {

        String json = """
                [{
                  "segment": "NSE_FO",
                  "name": "NIFTY",
                  "instrument_type": "CE",
                  "instrument_key": "NSE_FO|1",
                  "trading_symbol": "NIFTY 25000 CE 18 AUG 26",
                  "lot_size": 65,
                  "freeze_quantity": 1800,
                  "tick_size": 0.05,
                  "strike_price": 25000
                }]""";

        List<Instrument> instruments = parse(json);

        assertEquals(1, instruments.size());
        assertNull(instruments.get(0).getExpiry());
    }

    @Test
    void shouldSkipRecordsMissingTradingSymbolOrKey() throws IOException {

        String json = """
                [{
                  "segment": "NSE_FO",
                  "name": "NIFTY",
                  "instrument_type": "CE",
                  "trading_symbol": "NIFTY 25000 CE 18 AUG 26",
                  "strike_price": 25000
                }]""";

        assertTrue(parse(json).isEmpty());
    }
}
