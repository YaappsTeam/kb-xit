package com.kbquants.instrument;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for InstrumentRegistry.
 */
class InstrumentRegistryTest {

    private static Instrument of(String symbol, String key, String segment) {
        return new Instrument(symbol, key, segment, "EQ", symbol, 1, 0, 0.05);
    }

    private static InstrumentRegistry registry() {
        return new InstrumentRegistry(List.of(
                of("ACC", "NSE_EQ|INE012A01025", "NSE_EQ"),
                // The index's trading symbol really is just "NIFTY"; the
                // familiar "Nifty 50" lives only in its instrument key.
                of("NIFTY", "NSE_INDEX|Nifty 50", "NSE_INDEX"),
                of("SILVER", "NSE_EQ|INE123", "NSE_EQ"),
                of("SILVER", "NSE_COM|COM123", "NSE_COM"),
                of("CHOLAFIN", "NSE_EQ|INE111", "NSE_EQ"),
                of("CHOLAFIN", "NSE_EQ|INE222", "NSE_EQ")));
    }

    @Test
    void shouldResolveBySymbol() {
        assertEquals("NSE_EQ|INE012A01025", registry().resolve("ACC").orElseThrow().getInstrumentKey());
    }

    @Test
    void shouldResolveCaseInsensitively() {
        assertTrue(registry().resolve("acc").isPresent());
    }

    /**
     * The whole point of allowing NIFTY50: it matches nothing in the
     * master's trading_symbol column, only the alias derived from the
     * instrument key.
     */
    @Test
    void shouldResolveIndexByAliasFromItsKeyWithoutSpaces() {
        assertEquals("NSE_INDEX|Nifty 50", registry().resolve("NIFTY50").orElseThrow().getInstrumentKey());
    }

    @Test
    void shouldResolveIndexByAliasWithSpaces() {
        assertEquals("NSE_INDEX|Nifty 50", registry().resolve("NIFTY 50").orElseThrow().getInstrumentKey());
    }

    @Test
    void shouldStillResolveIndexByItsActualTradingSymbol() {
        assertEquals("NSE_INDEX|Nifty 50", registry().resolve("NIFTY").orElseThrow().getInstrumentKey());
    }

    /** Aliases are index-only; an equity's ISIN half is not a symbol. */
    @Test
    void shouldNotAliasNonIndexInstruments() {
        assertTrue(registry().candidatesFor("INE012A01025").isEmpty());
    }

    @Test
    void shouldResolveRawInstrumentKeyDirectly() {
        assertEquals("ACC", registry().resolve("NSE_EQ|INE012A01025").orElseThrow().getTradingSymbol());
    }

    @Test
    void shouldReturnEmptyForUnknownSymbol() {
        assertTrue(registry().resolve("NOSUCHTHING").isEmpty());
    }

    /** SILVER exists in both NSE_EQ and NSE_COM; equity wins. */
    @Test
    void shouldPreferEquitySegmentForCrossSegmentDuplicates() {
        assertEquals("NSE_EQ|INE123", registry().resolve("SILVER").orElseThrow().getInstrumentKey());
    }

    /**
     * CHOLAFIN appears twice within NSE_EQ. Guessing would mean trading a
     * coin-flip instrument, so resolution refuses and the caller reports
     * the candidates instead.
     */
    @Test
    void shouldRefuseToGuessBetweenSameSegmentDuplicates() {
        assertTrue(registry().resolve("CHOLAFIN").isEmpty());
        assertEquals(2, registry().candidatesFor("CHOLAFIN").size());
    }

    @Test
    void shouldReportNoCandidatesForUnknownSymbol() {
        assertTrue(registry().candidatesFor("NOSUCHTHING").isEmpty());
    }

    @Test
    void shouldCountLoadedInstruments() {
        assertEquals(6, registry().size());
    }
}
