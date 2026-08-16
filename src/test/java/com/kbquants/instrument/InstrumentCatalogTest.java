package com.kbquants.instrument;

import com.kbquants.session.QuoteService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for InstrumentCatalog: the on-demand refresh path and the
 * spot-driven strike-window narrowing, without touching the network.
 */
class InstrumentCatalogTest {

    private static final LocalDate EXPIRY = LocalDate.now().plusDays(3);

    private static Instrument option(double strike, String type) {
        String symbol = "NIFTY " + (int) strike + " " + type;
        return new Instrument(symbol, "NSE_FO|" + symbol, "NSE_FO", type, "NIFTY", 65, 1800, 0.05, strike, EXPIRY);
    }

    /** A chain from 24700 to 25300 step 50 (13 strikes), both CE and PE, one expiry. */
    private static InstrumentRegistry chain() {
        List<Instrument> options = new java.util.ArrayList<>();
        for (double strike = 24700; strike <= 25300; strike += 50) {
            options.add(option(strike, "CE"));
            options.add(option(strike, "PE"));
        }
        return new InstrumentRegistry(options);
    }

    private static InstrumentRegistry equityRegistry(String... symbols) {
        return new InstrumentRegistry(java.util.Arrays.stream(symbols)
                .map(s -> new Instrument(s, "NSE_EQ|" + s, "NSE_EQ", "EQ", s, 1, 0, 0.05))
                .toList());
    }

    /** Loader that serves the same chain each call, and can be made to fail. */
    private static final class StubLoader extends InstrumentMasterLoader {
        int loadCount;
        boolean fail;

        @Override
        public InstrumentRegistry load(boolean forceRefresh) throws IOException {
            loadCount++;
            if (fail) {
                throw new IOException("simulated network failure");
            }
            return chain();
        }
    }

    /** Fixed or failing spot price. */
    private static final class StubQuoteService implements QuoteService {
        volatile OptionalDouble price;

        StubQuoteService(double price) {
            this.price = OptionalDouble.of(price);
        }

        @Override
        public OptionalDouble lastTradedPrice(String instrumentKey) {
            return instrumentKey.equals(InstrumentCatalog.NIFTY_SPOT_KEY) ? price : OptionalDouble.empty();
        }
    }

    // ---- fixed catalog (unchanged behavior) ----

    @Test
    void fixedCatalogShouldExposeItsRegistry() {

        InstrumentRegistry registry = equityRegistry("ACC");

        assertSame(registry, new InstrumentCatalog(registry).registry());
    }

    @Test
    void fixedCatalogShouldReportThatItCannotRefresh() {

        String message = new InstrumentCatalog(equityRegistry("ACC")).refresh();

        assertTrue(message.contains("cannot be refreshed"));
    }

    // ---- loadFrom: windowing on initial load ----

    @Test
    void loadFromShouldWindowToStrikesNearSpot() throws IOException {

        InstrumentCatalog catalog = InstrumentCatalog.loadFrom(new StubLoader(), new StubQuoteService(25000));

        // 13 strikes total in the stub chain, window of 15 each side clamps
        // to all of them.
        assertEquals(26, catalog.registry().size());
    }

    @Test
    void loadFromShouldProduceAnEmptyRegistryWhenSpotIsUnavailable() throws IOException {

        StubQuoteService noSpot = new StubQuoteService(25000);
        noSpot.price = OptionalDouble.empty();

        InstrumentCatalog catalog = InstrumentCatalog.loadFrom(new StubLoader(), noSpot);

        assertEquals(0, catalog.registry().size());
    }

    // ---- refresh ----

    @Test
    void refreshShouldRecomputeTheWindowAgainstCurrentSpot() throws IOException {

        StubLoader loader = new StubLoader();
        StubQuoteService quotes = new StubQuoteService(25000);
        InstrumentCatalog catalog = InstrumentCatalog.loadFrom(loader, quotes);

        String message = catalog.refresh();

        assertEquals(2, loader.loadCount);
        assertTrue(message.contains("refreshed"));
        assertEquals(26, catalog.registry().size());
    }

    /**
     * A failed download must not leave the app with no instruments -- a
     * stale window beats none mid-session.
     */
    @Test
    void failedDownloadOnRefreshShouldKeepThePreviousRegistry() throws IOException {

        StubLoader loader = new StubLoader();
        InstrumentCatalog catalog = InstrumentCatalog.loadFrom(loader, new StubQuoteService(25000));
        InstrumentRegistry before = catalog.registry();

        loader.fail = true;
        String message = catalog.refresh();

        assertSame(before, catalog.registry());
        assertTrue(message.contains("failed"));
        assertTrue(message.contains("keeping the previous one"));
    }

    /**
     * A successful download but an unreachable spot price is a different
     * failure from a failed download -- same outcome (keep the previous
     * window), different message.
     */
    @Test
    void unavailableSpotOnRefreshShouldKeepThePreviousRegistry() throws IOException {

        StubLoader loader = new StubLoader();
        StubQuoteService quotes = new StubQuoteService(25000);
        InstrumentCatalog catalog = InstrumentCatalog.loadFrom(loader, quotes);
        InstrumentRegistry before = catalog.registry();

        quotes.price = OptionalDouble.empty();
        String message = catalog.refresh();

        assertSame(before, catalog.registry());
        assertTrue(message.contains("strike window could not be computed"));
        assertTrue(message.contains("keeping the previous"));
    }
}
