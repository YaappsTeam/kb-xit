package com.kbquants.instrument;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Narrows a NIFTY option chain down to the strikes actually worth tracking
 * today: the nearest expiry, and a fixed number of strike rungs on either
 * side of the one closest to spot.
 * <p>
 * Pure computation over already-loaded {@link Instrument}s -- no I/O, no
 * broker calls. {@link com.kbquants.instrument.InstrumentCatalog} is what
 * supplies the spot price and wires this in on a daily refresh; see
 * PRODUCT_REQUIREMENTS.md for why the window is recomputed once each
 * morning rather than re-centered on every tick.
 */
public final class StrikeWindow {

    private StrikeWindow() {
    }

    /**
     * The earliest expiry on or after {@code onOrAfter} present among the
     * given options, or empty if none qualifies (e.g. the whole chain has
     * already expired, or the master hasn't been refreshed since expiry).
     */
    public static Optional<LocalDate> nearestExpiry(Collection<Instrument> options, LocalDate onOrAfter) {
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(onOrAfter, "onOrAfter must not be null");
        return options.stream()
                .map(Instrument::getExpiry)
                .filter(Objects::nonNull)
                .filter(expiry -> !expiry.isBefore(onOrAfter))
                .min(Comparator.naturalOrder());
    }

    /**
     * Both CE and PE instruments, for the given expiry, whose strike is
     * within {@code rungsEachSide} of the strike nearest {@code spot}.
     * <p>
     * "Rung" means position in the sorted list of distinct strikes actually
     * listed for that expiry, not a fixed rupee distance -- NIFTY's strike
     * interval isn't constant across the full chain (50 near the money,
     * wider at the extremes), so counting rungs is what "15 above, 15
     * below" means in practice.
     * <p>
     * Clamped at the edges of the available chain rather than erroring: a
     * spot near either tail of the listed strikes legitimately has fewer
     * than {@code rungsEachSide} real strikes on that side.
     */
    public static List<Instrument> select(Collection<Instrument> options, LocalDate expiry, double spot,
                                           int rungsEachSide) {
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(expiry, "expiry must not be null");
        if (rungsEachSide < 0) {
            throw new IllegalArgumentException("rungsEachSide must not be negative: " + rungsEachSide);
        }

        List<Instrument> forExpiry = options.stream()
                .filter(i -> expiry.equals(i.getExpiry()))
                .toList();

        List<Double> strikes = forExpiry.stream()
                .map(Instrument::getStrikePrice)
                .distinct()
                .sorted()
                .toList();

        if (strikes.isEmpty()) {
            return List.of();
        }

        int nearest = nearestStrikeIndex(strikes, spot);
        int lo = Math.max(0, nearest - rungsEachSide);
        int hi = Math.min(strikes.size() - 1, nearest + rungsEachSide);
        Set<Double> selected = new HashSet<>(strikes.subList(lo, hi + 1));

        return forExpiry.stream().filter(i -> selected.contains(i.getStrikePrice())).toList();
    }

    private static int nearestStrikeIndex(List<Double> sortedStrikes, double spot) {
        int nearest = 0;
        double smallestDiff = Double.MAX_VALUE;
        for (int i = 0; i < sortedStrikes.size(); i++) {
            double diff = Math.abs(sortedStrikes.get(i) - spot);
            if (diff < smallestDiff) {
                smallestDiff = diff;
                nearest = i;
            }
        }
        return nearest;
    }
}
