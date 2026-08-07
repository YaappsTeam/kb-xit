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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Downloads and parses Upstox's NSE instrument master.
 * <p>
 * The file is public (no token needed), ~1.9 MB gzipped and ~37 MB of JSON
 * covering 80k+ instruments. It is cached on disk under a date-stamped
 * name, so it is fetched once per day and read locally thereafter --
 * instrument masters change daily as contracts are added and expire.
 * <p>
 * Parsing streams rather than materialising the whole document, and keeps
 * only the segments worth trading, which discards roughly half the records
 * (NSE_COM and NCD_FO) before they ever reach the heap.
 */
@Slf4j
public final class InstrumentMasterLoader {

    private static final String NSE_MASTER_URL =
            "https://assets.upstox.com/market-quote/instruments/exchange/NSE.json.gz";

    /** Equities, indices and F&O -- commodities and corporate debt are not in scope. */
    private static final Set<String> SEGMENTS_OF_INTEREST = Set.of("NSE_EQ", "NSE_INDEX", "NSE_FO");

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
        return new InstrumentRegistry(loadInstruments());
    }

    List<Instrument> loadInstruments() throws IOException {

        Path cached = cacheDir.resolve("NSE-" + LocalDate.now() + ".json.gz");

        if (!Files.exists(cached)) {
            Files.createDirectories(cacheDir);
            log.info("Downloading Upstox instrument master to {}", cached);
            try (InputStream in = openMaster()) {
                Files.copy(in, cached);
            }
            deleteStaleCaches(cached);
        } else {
            log.info("Using cached instrument master {}", cached);
        }

        try (JsonReader reader = new JsonReader(
                new InputStreamReader(new GZIPInputStream(Files.newInputStream(cached)), StandardCharsets.UTF_8))) {
            return parse(reader);
        }
    }

    private InputStream openMaster() throws IOException {
        URL url = URI.create(masterUrl).toURL();
        return url.openStream();
    }

    /**
     * Yesterday's master is dead weight once today's exists; expired
     * contracts in it are actively misleading.
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
            double tickSize = 0;

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
                    default -> reader.skipValue();
                }
            }
            reader.endObject();

            if (tradingSymbol != null && instrumentKey != null && SEGMENTS_OF_INTEREST.contains(segment)) {
                instruments.add(new Instrument(
                        tradingSymbol, instrumentKey, segment, instrumentType, name, lotSize, freezeQuantity, tickSize));
            }
        }

        reader.endArray();
        return instruments;
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
}
