package com.kbquants.instrument;

import com.google.gson.stream.JsonReader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Downloads and parses Upstox's NSE instrument master, keeping only NIFTY
 * index options.
 * <p>
 * <b>Scope, as of Phase 3.5:</b> this repo currently manages NIFTY 50
 * option trades only -- not equities, not other indices, not futures. See
 * PRODUCT_REQUIREMENTS.md for why. That scope is enforced here, at parse
 * time, rather than left to callers to filter: keeping equities and other
 * underlyings in memory would cost real heap for records nothing downstream
 * will ever look at.
 * <p>
 * The source file itself is unavoidable overhead: Upstox publishes one
 * combined file for the whole NSE segment (~1.9 MB gzipped, ~37 MB of JSON,
 * 80k+ instruments across equities, indices and every F&O underlying) --
 * there is no NIFTY-only or options-only endpoint, so the download size is
 * fixed regardless of scope. What scope narrows is what survives parsing:
 * roughly 40k+ records down to the low hundreds/thousands that are NIFTY
 * CE/PE contracts, which is what actually matters for parse time, retained
 * heap, and {@link InstrumentRegistry}'s lookup-index size.
 * <p>
 * <b>Refreshed daily</b>, not weekly -- now that the retained dataset is
 * this small, a daily download is cheap enough that there is no reason to
 * accept the staleness a weekly cache implies (a contract listed since the
 * last refresh not resolving, or an expired one lingering). The cache is
 * stamped with today's date, so the first {@code /track} or process start
 * of a given day downloads, and every later one that same day reads from
 * disk. {@link #load(boolean)} with {@code forceRefresh} bypasses that.
 * <p>
 * Parsing streams rather than materialising the whole document.
 */
@Slf4j
public class InstrumentMasterLoader {

    private static final String NSE_MASTER_URL =
            "https://assets.upstox.com/market-quote/instruments/exchange/NSE.json.gz";

    private static final String OPTIONS_SEGMENT = "NSE_FO";
    private static final Set<String> OPTION_TYPES = Set.of("CE", "PE");

    /**
     * Matched against the parsed {@code name} field, which this loader
     * already relies on elsewhere (see InstrumentAwareTrackRequestResolver's
     * ambiguity messages) and is confirmed present and populated for F&O
     * records. Case-insensitive: Upstox's own casing for this field has not
     * been verified against a live payload in this sandbox (outbound access
     * to upstox.com is blocked here -- see DEVELOPMENT.md), so matching
     * loosely is the safer assumption than matching exactly and silently
     * keeping zero contracts if the real casing differs.
     */
    private static final String NIFTY_UNDERLYING = "NIFTY";

    /**
     * Market-hours zone for interpreting the expiry timestamp as a
     * calendar date, consistent with every other market-time decision in
     * this codebase (see TradingToken, EndOfDaySchedule).
     */
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private final String masterUrl;
    private final Path cacheDir;

    public InstrumentMasterLoader() {
        this(NSE_MASTER_URL, defaultCacheDir());
    }

    public InstrumentMasterLoader(String masterUrl, Path cacheDir) {
        this.masterUrl = masterUrl;
        this.cacheDir = cacheDir;
    }

    private static Path defaultCacheDir() {
        String override = System.getenv("INSTRUMENT_CACHE_DIR");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".xit-mc", "instruments");
    }

    public InstrumentRegistry load() throws IOException {
        return load(false);
    }

    public InstrumentRegistry load(boolean forceRefresh) throws IOException {
        return new InstrumentRegistry(loadInstruments(forceRefresh));
    }

    List<Instrument> loadInstruments(boolean forceRefresh) throws IOException {

        Path cached = cacheFile(LocalDate.now(MARKET_ZONE));

        if (forceRefresh || !Files.exists(cached)) {
            Files.createDirectories(cacheDir);
            log.info("Downloading Upstox instrument master to {}{}", cached, forceRefresh ? " (forced)" : "");
            try (InputStream in = openMaster()) {
                Files.copy(in, cached, StandardCopyOption.REPLACE_EXISTING);
            }
            deleteStaleCaches(cached);
        } else {
            log.info("Using cached instrument master {} (refreshes daily)", cached);
        }

        try (JsonReader reader = new JsonReader(
                new InputStreamReader(new GZIPInputStream(Files.newInputStream(cached)), StandardCharsets.UTF_8))) {
            return parse(reader);
        }
    }

    Path cacheFile(LocalDate day) {
        return cacheDir.resolve("NSE-" + day + ".json.gz");
    }

    private InputStream openMaster() throws IOException {
        URL url = URI.create(masterUrl).toURL();
        return url.openStream();
    }

    /**
     * Yesterday's master is dead weight once today's exists; a strike
     * window computed from a stale chain is actively misleading.
     */
    private void deleteStaleCaches(Path keep) {
        try (var entries = Files.list(cacheDir)) {
            entries.filter(p -> !p.equals(keep))
                    .filter(p -> p.getFileName().toString().startsWith("NSE-"))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                            log.debug("Deleted stale instrument cache {}", p);
                        } catch (IOException e) {
                            log.warn("Could not delete stale instrument cache {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Could not scan instrument cache dir {}: {}", cacheDir, e.getMessage());
        }
    }

    static List<Instrument> parse(JsonReader reader) throws IOException {

        List<Instrument> instruments = new ArrayList<>();
        reader.beginArray();

        while (reader.hasNext()) {
            reader.beginObject();

            String tradingSymbol = null, instrumentKey = null, segment = null, instrumentType = null, name = null;
            int lotSize = 0, freezeQuantity = 0;
            double tickSize = 0, strikePrice = 0;
            Long expiryMillis = null;

            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "trading_symbol" -> tradingSymbol = nextStringOrNull(reader);
                    case "instrument_key" -> instrumentKey = nextStringOrNull(reader);
                    case "segment" -> segment = nextStringOrNull(reader);
                    case "instrument_type" -> instrumentType = nextStringOrNull(reader);
                    case "name" -> name = nextStringOrNull(reader);
                    case "lot_size" -> lotSize = (int) nextDoubleOrZero(reader);
                    case "freeze_quantity" -> freezeQuantity = (int) nextDoubleOrZero(reader);
                    case "tick_size" -> tickSize = nextDoubleOrZero(reader);
                    // strike_price and expiry: not yet verified against a
                    // live Upstox payload (see class Javadoc). If Upstox's
                    // real field names differ, every record below fails the
                    // isOptionOfInterest check and the registry loads empty
                    // rather than wrong -- see the defensive skip below.
                    case "strike_price" -> strikePrice = nextDoubleOrZero(reader);
                    case "expiry" -> expiryMillis = nextLongOrNull(reader);
                    default -> reader.skipValue();
                }
            }
            reader.endObject();

            if (isOptionOfInterest(segment, instrumentType, name, tradingSymbol, instrumentKey)) {
                LocalDate expiry = expiryMillis == null ? null
                        : Instant.ofEpochMilli(expiryMillis).atZone(MARKET_ZONE).toLocalDate();
                instruments.add(new Instrument(tradingSymbol, instrumentKey, segment, instrumentType, name,
                        lotSize, freezeQuantity, tickSize, strikePrice, expiry));
            }
        }

        reader.endArray();
        return instruments;
    }

    private static boolean isOptionOfInterest(String segment, String instrumentType, String name,
                                               String tradingSymbol, String instrumentKey) {
        return tradingSymbol != null
                && instrumentKey != null
                && OPTIONS_SEGMENT.equals(segment)
                && instrumentType != null && OPTION_TYPES.contains(instrumentType)
                && name != null && NIFTY_UNDERLYING.equalsIgnoreCase(name.trim());
    }

    private static String nextStringOrNull(JsonReader reader) throws IOException {
        if (reader.peek() == com.google.gson.stream.JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return reader.nextString();
    }

    private static double nextDoubleOrZero(JsonReader reader) throws IOException {
        if (reader.peek() == com.google.gson.stream.JsonToken.NULL) {
            reader.nextNull();
            return 0;
        }
        return reader.nextDouble();
    }

    private static Long nextLongOrNull(JsonReader reader) throws IOException {
        if (reader.peek() == com.google.gson.stream.JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return (long) reader.nextDouble();
    }
}
