package com.kbquants.instrument;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Objects;

/**
 * Holds the current {@link InstrumentRegistry} and allows it to be swapped
 * for a freshly downloaded one without restarting.
 * <p>
 * The registry itself is immutable; this is the one mutable seam, and the
 * reference is volatile because a refresh runs on the Telegram poller
 * thread while /buy resolution reads it from wherever the command arrived.
 * A refresh that fails leaves the previous registry in place -- a stale
 * instrument master is far better than none mid-session.
 */
@Slf4j
public final class InstrumentCatalog {

    private final InstrumentMasterLoader loader;
    private volatile InstrumentRegistry registry;

    /** Fixed catalog that cannot refresh -- used in tests. */
    public InstrumentCatalog(InstrumentRegistry registry) {
        this.loader = null;
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    private InstrumentCatalog(InstrumentMasterLoader loader, InstrumentRegistry registry) {
        this.loader = loader;
        this.registry = registry;
    }

    public static InstrumentCatalog loadFrom(InstrumentMasterLoader loader) throws IOException {
        Objects.requireNonNull(loader, "loader must not be null");
        return new InstrumentCatalog(loader, loader.load());
    }

    public InstrumentRegistry registry() {
        return registry;
    }

    /**
     * Forces a download regardless of the weekly schedule. Returns a
     * message describing the outcome, for reporting straight back to the
     * user who asked for it.
     */
    public String refresh() {

        if (loader == null) {
            return "instrument master is fixed in this configuration and cannot be refreshed";
        }

        int before = registry.size();
        try {
            registry = loader.load(true);
            log.info("Instrument master refreshed on demand: {} -> {} instruments", before, registry.size());
            return String.format("instrument master refreshed: %d instruments (was %d)", registry.size(), before);
        } catch (IOException e) {
            log.error("On-demand instrument master refresh failed", e);
            return "instrument master refresh failed: " + e.getMessage() + " (keeping the previous one)";
        }
    }
}
