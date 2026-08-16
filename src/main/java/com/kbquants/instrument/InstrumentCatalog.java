package com.kbquants.instrument;

import com.kbquants.session.QuoteService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Holds today's tradable NIFTY option window and allows it to be
 * recomputed without restarting.
 * <p>
 * {@link InstrumentMasterLoader} returns every current NIFTY CE/PE
 * contract across every listed expiry; this class narrows that down
 * further, to the single instrument set that actually matters right now:
 * the nearest expiry, and the 15 strikes on either side of the one
 * closest to spot ({@link StrikeWindow}). That narrower set is what
 * {@link #registry()} returns -- callers never see the wider chain.
 * <p>
 * The registry itself is immutable; this is the one mutable seam, and the
 * reference is volatile because a refresh runs on the Telegram poller
 * thread (or the daily schedule -- see Main) while /track resolution
 * reads it from wherever the command arrived. A refresh that fails leaves
 * the previous window in place -- a stale window is far better than none
 * mid-session.
 */
@Slf4j
public final class InstrumentCatalog {

    /** NSE's Nifty 50 index -- the one spot price this scope needs. */
    public static final String NIFTY_SPOT_KEY = "NSE_INDEX|Nifty 50";

    /**
     * "15 above, 15 below" -- see PRODUCT_REQUIREMENTS.md. A code constant
     * rather than configuration, for the same reason MilestoneLadder's
     * numbers are: it encodes trading intent worth reviewing in a diff,
     * not a knob to turn per deployment.
     */
    static final int RUNGS_EACH_SIDE = 15;

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private final InstrumentMasterLoader loader;
    private final QuoteService quoteService;
    private volatile InstrumentRegistry registry;

    /** Fixed catalog that cannot refresh -- used in tests. */
    public InstrumentCatalog(InstrumentRegistry registry) {
        this.loader = null;
        this.quoteService = null;
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    private InstrumentCatalog(InstrumentMasterLoader loader, QuoteService quoteService, InstrumentRegistry registry) {
        this.loader = loader;
        this.quoteService = quoteService;
        this.registry = registry;
    }

    /**
     * Downloads (or reads the day's cache of) the NIFTY option chain, then
     * narrows it to today's window using {@code quoteService} for spot.
     * If the window can't be computed -- no spot price, or no upcoming
     * expiry in the loaded chain -- starts with zero tracked instruments
     * rather than falling back to the unwindowed chain, which would
     * silently defeat the point of narrowing at all. A subsequent
     * /refresh retries once the market/quote API is reachable.
     */
    public static InstrumentCatalog loadFrom(InstrumentMasterLoader loader, QuoteService quoteService)
            throws IOException {
        Objects.requireNonNull(loader, "loader must not be null");
        Objects.requireNonNull(quoteService, "quoteService must not be null");

        InstrumentRegistry raw = loader.load();
        InstrumentRegistry windowed = window(raw, quoteService).orElseGet(() -> {
            log.warn("Could not compute today's NIFTY strike window at startup (no spot price, or no upcoming "
                    + "expiry in the loaded chain) -- starting with zero tracked instruments. Send /refresh once "
                    + "the market/quote API is reachable.");
            return new InstrumentRegistry(List.of());
        });
        return new InstrumentCatalog(loader, quoteService, windowed);
    }

    public InstrumentRegistry registry() {
        return registry;
    }

    /**
     * Forces a fresh download regardless of the daily cache, and
     * recomputes today's window against current spot. Returns a message
     * describing the outcome, for reporting straight back to the user who
     * asked for it.
     */
    public String refresh() {

        if (loader == null) {
            return "instrument master is fixed in this configuration and cannot be refreshed";
        }

        int before = registry.size();

        InstrumentRegistry raw;
        try {
            raw = loader.load(true);
        } catch (IOException e) {
            log.error("On-demand instrument master refresh failed", e);
            return "instrument master refresh failed: " + e.getMessage() + " (keeping the previous one)";
        }

        Optional<InstrumentRegistry> windowed = window(raw, quoteService);
        if (windowed.isEmpty()) {
            log.warn("Instrument master downloaded, but today's strike window could not be computed -- "
                    + "keeping the previous one");
            return "instrument master downloaded, but today's strike window could not be computed "
                    + "(no spot price, or no upcoming expiry) -- keeping the previous " + before + " instrument(s)";
        }

        registry = windowed.get();
        log.info("Instrument master refreshed: {} -> {} instruments (today's NIFTY window)", before, registry.size());
        return String.format("instrument master refreshed: %d instruments (was %d)", registry.size(), before);
    }

    /**
     * Empty when either input needed to narrow the chain is missing --
     * distinguishing "narrowed to nothing because the chain is genuinely
     * empty right now" (a valid, if unlikely, {@link InstrumentRegistry}
     * of size 0) from "couldn't narrow at all" (this method's Optional).
     */
    private static Optional<InstrumentRegistry> window(InstrumentRegistry raw, QuoteService quoteService) {

        OptionalDouble spot = quoteService.lastTradedPrice(NIFTY_SPOT_KEY);
        if (spot.isEmpty()) {
            log.warn("Could not fetch NIFTY 50 spot price ({})", NIFTY_SPOT_KEY);
            return Optional.empty();
        }

        List<Instrument> options = List.copyOf(raw.all());
        LocalDate today = LocalDate.now(MARKET_ZONE);
        Optional<LocalDate> expiry = StrikeWindow.nearestExpiry(options, today);
        if (expiry.isEmpty()) {
            log.warn("No NIFTY option expiry on or after {} in the loaded chain", today);
            return Optional.empty();
        }

        List<Instrument> selected = StrikeWindow.select(options, expiry.get(), spot.getAsDouble(), RUNGS_EACH_SIDE);
        log.info("Today's NIFTY window: expiry {}, spot {}, {} instrument(s) within {} strikes each side",
                expiry.get(), spot.getAsDouble(), selected.size(), RUNGS_EACH_SIDE);
        return Optional.of(new InstrumentRegistry(selected));
    }
}
