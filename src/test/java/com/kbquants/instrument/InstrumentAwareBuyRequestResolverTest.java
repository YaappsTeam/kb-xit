package com.kbquants.instrument;

import com.kbquants.session.BuyRequest;
import com.kbquants.session.QuoteService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for InstrumentAwareBuyRequestResolver, using a stubbed quote
 * service so no network call is made.
 */
class InstrumentAwareBuyRequestResolverTest {

    private static final Instrument ACC =
            new Instrument("ACC", "NSE_EQ|INE012A01025", "NSE_EQ", "EQ", "ACC LIMITED", 1, 0, 0.10);
    /** As it actually appears in Upstox's master: symbol "NIFTY", key "…|Nifty 50". */
    private static final Instrument NIFTY =
            new Instrument("NIFTY", "NSE_INDEX|Nifty 50", "NSE_INDEX", "INDEX", "Nifty 50", 1, 0, 0.05);
    private static final Instrument NIFTY_CE =
            new Instrument("NIFTY 25000 CE 18 AUG 26", "NSE_FO|45148", "NSE_FO", "CE", "NIFTY", 65, 1755, 0.05);
    private static final Instrument CHOLA_A =
            new Instrument("CHOLAFIN", "NSE_EQ|INE111", "NSE_EQ", "EQ", "Cholamandalam A", 1, 0, 0.05);
    private static final Instrument CHOLA_B =
            new Instrument("CHOLAFIN", "NSE_EQ|INE222", "NSE_EQ", "EQ", "Cholamandalam B", 1, 0, 0.05);

    private static InstrumentAwareBuyRequestResolver resolver(Map<String, Double> prices, double capital) {
        QuoteService quotes = key -> prices.containsKey(key)
                ? OptionalDouble.of(prices.get(key))
                : OptionalDouble.empty();
        return new InstrumentAwareBuyRequestResolver(
                new InstrumentRegistry(List.of(ACC, NIFTY, NIFTY_CE, CHOLA_A, CHOLA_B)),
                quotes,
                new PositionSizer(capital));
    }

    private static InstrumentAwareBuyRequestResolver defaultResolver() {
        return resolver(Map.of(
                "NSE_EQ|INE012A01025", 1850.50,
                "NSE_INDEX|Nifty 50", 24570.65,
                "NSE_FO|45148", 150.0), 50_000);
    }

    @Test
    void shouldResolveBareSymbolWithDefaultedPriceAndQuantity() {

        BuyRequest request = defaultResolver().resolve(List.of("ACC"));

        assertTrue(request.isAccepted());
        assertEquals("NSE_EQ|INE012A01025", request.getInstrumentKey());
        assertEquals(1850.50, request.getPrice());
        assertEquals(27, request.getQuantity());
    }

    @Test
    void shouldResolveSymbolWithExplicitQuantityOnly() {

        BuyRequest request = defaultResolver().resolve(List.of("ACC", "25"));

        assertTrue(request.isAccepted());
        assertEquals(1850.50, request.getPrice());
        assertEquals(25, request.getQuantity());
    }

    @Test
    void shouldResolveSymbolWithExplicitPriceAndQuantity() {

        BuyRequest request = defaultResolver().resolve(List.of("ACC", "1800", "10"));

        assertTrue(request.isAccepted());
        assertEquals(1800.0, request.getPrice());
        assertEquals(10, request.getQuantity());
    }

    /**
     * The ambiguity the positional grammar creates: an option symbol is
     * six tokens, so a naive parse would read the trailing "26" as a
     * quantity. Longest-first matching settles it.
     */
    @Test
    void shouldPreferLongerSymbolMatchOverTrailingQuantity() {

        BuyRequest request = defaultResolver().resolve(List.of("NIFTY", "25000", "CE", "18", "AUG", "26"));

        assertTrue(request.isAccepted());
        assertEquals("NSE_FO|45148", request.getInstrumentKey());
        assertEquals(325, request.getQuantity());
    }

    @Test
    void shouldStillAllowQuantityAfterAMultiTokenSymbol() {

        BuyRequest request = defaultResolver().resolve(List.of("NIFTY", "25000", "CE", "18", "AUG", "26", "130"));

        assertTrue(request.isAccepted());
        assertEquals("NSE_FO|45148", request.getInstrumentKey());
        assertEquals(130, request.getQuantity());
    }

    /**
     * The index's trading symbol is "NIFTY"; "NIFTY50" only resolves via
     * the alias taken from its instrument key.
     */
    @Test
    void shouldResolveIndexBySpacelessAliasFromItsKey() {

        BuyRequest request = defaultResolver().resolve(List.of("NIFTY50"));

        assertFalse(request.isAccepted());
        assertTrue(request.getRejectionReason().contains("is an index"));
    }

    /**
     * An index has no position behind it, so buying one is refused rather
     * than opening a paper trade that could never be real.
     */
    @Test
    void shouldRejectBuyingAnIndex() {

        BuyRequest request = defaultResolver().resolve(List.of("NIFTY", "50"));

        assertFalse(request.isAccepted());
        assertTrue(request.getRejectionReason().contains("cannot be bought"));
    }

    @Test
    void shouldSizeOptionsInWholeLots() {

        BuyRequest request = defaultResolver().resolve(List.of("nifty25000ce18aug26"));

        assertTrue(request.isAccepted());
        assertEquals(325, request.getQuantity());
        assertTrue(request.getNote().contains("5 lots"));
    }

    @Test
    void shouldCapOptionQuantityAtFreezeLimitAndSaySo() {

        BuyRequest request = resolver(Map.of("NSE_FO|45148", 150.0), 10_000_000)
                .resolve(List.of("nifty25000ce18aug26"));

        assertTrue(request.isAccepted());
        assertEquals(1755, request.getQuantity());
        assertTrue(request.getNote().contains("freeze limit"));
    }

    @Test
    void shouldRejectWhenCapitalCannotCoverOneLot() {

        BuyRequest request = resolver(Map.of("NSE_FO|45148", 900.0), 10_000)
                .resolve(List.of("nifty25000ce18aug26"));

        assertFalse(request.isAccepted());
        assertTrue(request.getRejectionReason().contains("exceeds capital per trade"));
    }

    @Test
    void shouldRejectUnknownSymbol() {

        BuyRequest request = defaultResolver().resolve(List.of("NOSUCHTHING"));

        assertFalse(request.isAccepted());
        assertTrue(request.getRejectionReason().contains("unknown instrument"));
    }

    @Test
    void shouldRejectAmbiguousSymbolAndListCandidates() {

        BuyRequest request = defaultResolver().resolve(List.of("CHOLAFIN"));

        assertFalse(request.isAccepted());
        assertTrue(request.getRejectionReason().contains("ambiguous"));
        assertTrue(request.getRejectionReason().contains("NSE_EQ|INE111"));
        assertTrue(request.getRejectionReason().contains("NSE_EQ|INE222"));
    }

    /**
     * Without a price there is nothing to size against, so the user is told
     * to supply one rather than having a stale or invented number used.
     */
    @Test
    void shouldRejectWhenLastTradedPriceIsUnavailable() {

        BuyRequest request = resolver(Map.of(), 50_000).resolve(List.of("ACC"));

        assertFalse(request.isAccepted());
        assertTrue(request.getRejectionReason().contains("could not fetch last traded price"));
    }

    @Test
    void shouldStillAcceptRawInstrumentKey() {

        BuyRequest request = defaultResolver().resolve(List.of("NSE_EQ|INE012A01025", "1800", "10"));

        assertTrue(request.isAccepted());
        assertEquals(10, request.getQuantity());
    }

    @Test
    void shouldRejectNonPositiveExplicitQuantity() {
        assertFalse(defaultResolver().resolve(List.of("ACC", "0")).isAccepted());
    }

    @Test
    void shouldRejectEmptyArguments() {
        assertFalse(defaultResolver().resolve(List.of()).isAccepted());
    }
}
