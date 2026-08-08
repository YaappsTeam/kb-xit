package com.kbquants.instrument;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for InstrumentCatalog, covering the on-demand refresh path
 * without touching the network.
 */
class InstrumentCatalogTest {

    private static Instrument instrument(String symbol) {
        return new Instrument(symbol, "NSE_EQ|" + symbol, "NSE_EQ", "EQ", symbol, 1, 0, 0.05);
    }

    private static InstrumentRegistry registryOf(String... symbols) {
        return new InstrumentRegistry(Arrays.stream(symbols).map(InstrumentCatalogTest::instrument).toList());
    }

    /** Loader that serves a new registry each call, and can be made to fail. */
    private static final class StubLoader extends InstrumentMasterLoader {
        int loadCount;
        boolean fail;

        @Override
        public InstrumentRegistry load(boolean forceRefresh) throws IOException {
            loadCount++;
            if (fail) {
                throw new IOException("simulated network failure");
            }
            return registryOf("ACC", "RELIANCE", "TCS");
        }
    }

    @Test
    void fixedCatalogShouldExposeItsRegistry() {

        InstrumentRegistry registry = registryOf("ACC");

        assertSame(registry, new InstrumentCatalog(registry).registry());
    }

    @Test
    void fixedCatalogShouldReportThatItCannotRefresh() {

        String message = new InstrumentCatalog(registryOf("ACC")).refresh();

        assertTrue(message.contains("cannot be refreshed"));
    }

    @Test
    void refreshShouldSwapInTheNewRegistry() throws IOException {

        StubLoader loader = new StubLoader();
        InstrumentCatalog catalog = InstrumentCatalog.loadFrom(loader);

        assertEquals(3, catalog.registry().size());

        String message = catalog.refresh();

        assertEquals(2, loader.loadCount);
        assertTrue(message.contains("refreshed"));
    }

    /**
     * A failed refresh must not leave the app with no instruments -- a
     * stale master beats none mid-session.
     */
    @Test
    void failedRefreshShouldKeepThePreviousRegistry() throws IOException {

        StubLoader loader = new StubLoader();
        InstrumentCatalog catalog = InstrumentCatalog.loadFrom(loader);
        InstrumentRegistry before = catalog.registry();

        loader.fail = true;
        String message = catalog.refresh();

        assertSame(before, catalog.registry());
        assertTrue(message.contains("failed"));
        assertTrue(message.contains("keeping the previous one"));
    }
}
