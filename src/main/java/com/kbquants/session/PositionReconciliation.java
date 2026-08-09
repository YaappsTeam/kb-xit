package com.kbquants.session;

import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares what this system thinks it is managing against what the broker
 * says is open.
 * <p>
 * Pure and static: the comparison is the part worth getting right, and it
 * is easier to be sure of when it cannot reach a network, a clock or a
 * trade store.
 * <p>
 * Matching is by instrument, not by order id, because the broker reports
 * positions rather than orders -- two trades on the same strike are one
 * position to it. That means a discrepancy on an instrument implicates
 * every trade this system holds on that instrument: it can tell that 5 of
 * 10 lots are gone, but not which trade they came from.
 */
public final class PositionReconciliation {

    private PositionReconciliation() {
    }

    /** What this system believes it is managing. */
    @Getter
    public static final class TrackedPosition {
        private final String orderId;
        private final String instrumentKey;
        private final String displaySymbol;
        private final int quantity;

        /** False for RELEASED and OBSERVED trades, which the engine already will not act on. */
        private final boolean managed;

        public TrackedPosition(String orderId, String instrumentKey, String displaySymbol,
                               int quantity, boolean managed) {
            this.orderId = orderId;
            this.instrumentKey = instrumentKey;
            this.displaySymbol = displaySymbol;
            this.quantity = quantity;
            this.managed = managed;
        }
    }

    public enum Kind {

        /**
         * The broker holds nothing on this instrument. Whatever this system
         * is watching was closed elsewhere, or never opened.
         */
        GONE,

        /**
         * The broker holds less than this system is tracking -- part of the
         * position was sold by hand.
         */
        REDUCED
    }

    /**
     * A tracked trade the broker does not agree with.
     * <p>
     * Both kinds are dangerous in the same way: an exit sized from what
     * this system remembers would sell quantity that is no longer there,
     * and a sell beyond a flat position is a short -- a new, unmanaged
     * trade in the opposite direction, opened by the thing meant to be
     * closing trades.
     */
    @Getter
    public static final class Discrepancy {
        private final TrackedPosition tracked;
        private final Kind kind;
        private final int brokerQuantity;
        private final int trackedQuantity;

        Discrepancy(TrackedPosition tracked, Kind kind, int brokerQuantity, int trackedQuantity) {
            this.tracked = tracked;
            this.kind = kind;
            this.brokerQuantity = brokerQuantity;
            this.trackedQuantity = trackedQuantity;
        }

        public String describe() {
            if (kind == Kind.GONE) {
                return String.format("%s: not open at your broker (this system has %d)",
                        tracked.getDisplaySymbol(), trackedQuantity);
            }
            return String.format("%s: broker has %d, this system has %d",
                    tracked.getDisplaySymbol(), brokerQuantity, trackedQuantity);
        }
    }

    @Getter
    public static final class Report {
        private final List<TrackedPosition> confirmed;
        private final List<Discrepancy> discrepancies;
        private final List<BrokerPosition> untracked;

        Report(List<TrackedPosition> confirmed, List<Discrepancy> discrepancies, List<BrokerPosition> untracked) {
            this.confirmed = List.copyOf(confirmed);
            this.discrepancies = List.copyOf(discrepancies);
            this.untracked = List.copyOf(untracked);
        }

        public boolean isClean() {
            return discrepancies.isEmpty() && untracked.isEmpty();
        }
    }

    public static Report compare(List<TrackedPosition> tracked, List<BrokerPosition> brokerPositions) {

        Map<String, Integer> brokerQuantities = new LinkedHashMap<>();
        Map<String, BrokerPosition> brokerByInstrument = new LinkedHashMap<>();
        for (BrokerPosition position : brokerPositions) {
            // Shorts and closed-out rows are not positions this system could
            // ever be managing: it only sells what was bought elsewhere.
            if (!position.isLong()) {
                continue;
            }
            brokerQuantities.merge(position.getInstrumentKey(), position.getQuantity(), Integer::sum);
            brokerByInstrument.putIfAbsent(position.getInstrumentKey(), position);
        }

        Map<String, Integer> trackedQuantities = new LinkedHashMap<>();
        for (TrackedPosition position : tracked) {
            trackedQuantities.merge(position.getInstrumentKey(), position.getQuantity(), Integer::sum);
        }

        List<TrackedPosition> confirmed = new ArrayList<>();
        List<Discrepancy> discrepancies = new ArrayList<>();

        for (TrackedPosition position : tracked) {
            int brokerQuantity = brokerQuantities.getOrDefault(position.getInstrumentKey(), 0);
            int trackedTotal = trackedQuantities.get(position.getInstrumentKey());

            if (brokerQuantity <= 0) {
                discrepancies.add(new Discrepancy(position, Kind.GONE, 0, trackedTotal));
            } else if (brokerQuantity < trackedTotal) {
                discrepancies.add(new Discrepancy(position, Kind.REDUCED, brokerQuantity, trackedTotal));
            } else {
                confirmed.add(position);
            }
        }

        List<BrokerPosition> untracked = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : brokerQuantities.entrySet()) {
            int surplus = entry.getValue() - trackedQuantities.getOrDefault(entry.getKey(), 0);
            if (surplus <= 0) {
                continue;
            }
            BrokerPosition seen = brokerByInstrument.get(entry.getKey());
            // The surplus, not the whole position: the part already being
            // managed must not be offered for adoption a second time.
            untracked.add(new BrokerPosition(seen.getInstrumentKey(), surplus, seen.getAveragePrice(),
                    seen.getDisplaySymbol(), seen.getProduct()));
        }

        return new Report(confirmed, discrepancies, untracked);
    }
}
