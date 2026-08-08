package com.kbquants.instrument;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory symbol lookup over the Upstox instrument master.
 * <p>
 * Deliberately a local map rather than an API call: resolution sits in the
 * hot path of a scalping command, where a network round trip per /track is
 * exactly what we are trying to avoid.
 * <p>
 * Lookup is case- and whitespace-insensitive, so all of "NIFTY 50",
 * "NIFTY50" and "nifty50" find the index, and an option can be typed as
 * "nifty25000ce18aug26" rather than with the master file's exact spacing.
 * <p>
 * Trading symbols are <b>not</b> unique across the master -- a handful
 * (CHOLAFIN, MOTHERSON, ELECTCAST, IMC1, SILVER) appear more than once.
 * Where the duplicates span segments the equity/index one wins; where they
 * do not, {@link #resolve} deliberately returns empty rather than guessing,
 * and {@link #candidatesFor} supplies the alternatives to show the user.
 * Silently picking one would mean trading a coin-flip instrument.
 */
@Slf4j
public final class InstrumentRegistry {

    /** Segments preferred when the same symbol appears in more than one. */
    private static final List<String> SEGMENT_PRIORITY = List.of("NSE_EQ", "NSE_INDEX", "NSE_FO");

    private final Map<String, List<Instrument>> bySymbol = new HashMap<>();
    private final Map<String, Instrument> byKey = new HashMap<>();

    public InstrumentRegistry(Collection<Instrument> instruments) {
        Objects.requireNonNull(instruments, "instruments must not be null");
        for (Instrument instrument : instruments) {
            index(normalise(instrument.getTradingSymbol()), instrument);
            indexAlias(instrument);
            byKey.put(instrument.getInstrumentKey(), instrument);
        }
        log.info("Instrument registry loaded: {} instruments, {} distinct symbols", byKey.size(), bySymbol.size());
    }

    private void index(String symbol, Instrument instrument) {
        bySymbol.computeIfAbsent(symbol, k -> new ArrayList<>()).add(instrument);
    }

    /**
     * Indices carry a trading symbol that differs from the name everyone
     * actually uses: the Nifty 50 index is "NIFTY" with the key
     * "NSE_INDEX|Nifty 50". Registering the descriptive half of the key as
     * an alias is what makes "NIFTY50" resolve, rather than only "NIFTY".
     * <p>
     * Restricted to indices deliberately -- for equities that half is an
     * ISIN and for F&O a numeric token, neither of which anyone types, and
     * both of which already resolve as full keys.
     */
    private void indexAlias(Instrument instrument) {
        if (!"NSE_INDEX".equals(instrument.getSegment())) {
            return;
        }
        int pipe = instrument.getInstrumentKey().indexOf('|');
        if (pipe < 0 || pipe == instrument.getInstrumentKey().length() - 1) {
            return;
        }
        String alias = normalise(instrument.getInstrumentKey().substring(pipe + 1));
        if (!alias.equals(normalise(instrument.getTradingSymbol()))) {
            index(alias, instrument);
        }
    }

    public int size() {
        return byKey.size();
    }

    /**
     * Resolves a raw instrument key ("NSE_EQ|INE012A01025") or a trading
     * symbol ("ACC", "nifty50"). Returns empty when unknown, or when a
     * symbol is ambiguous within a single segment.
     */
    public Optional<Instrument> resolve(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        String trimmed = text.trim();
        Instrument direct = byKey.get(trimmed);
        if (direct != null) {
            return Optional.of(direct);
        }

        List<Instrument> matches = bySymbol.get(normalise(trimmed));
        if (matches == null || matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() == 1) {
            return Optional.of(matches.get(0));
        }
        return disambiguate(matches);
    }

    /**
     * All instruments a symbol could refer to -- used to explain an
     * ambiguous {@link #resolve} back to the user.
     */
    public List<Instrument> candidatesFor(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.copyOf(bySymbol.getOrDefault(normalise(text), List.of()));
    }

    /**
     * Picks a winner only when the segment priority makes it unambiguous:
     * exactly one candidate sits in the highest-priority segment present.
     * Same-segment duplicates stay unresolved by design.
     */
    private Optional<Instrument> disambiguate(List<Instrument> matches) {
        for (String segment : SEGMENT_PRIORITY) {
            List<Instrument> inSegment = matches.stream()
                    .filter(i -> segment.equals(i.getSegment()))
                    .toList();
            if (inSegment.size() == 1) {
                return Optional.of(inSegment.get(0));
            }
            if (inSegment.size() > 1) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    static String normalise(String symbol) {
        return symbol.replaceAll("\\s+", "").toUpperCase();
    }
}
