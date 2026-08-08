package com.kbquants.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A named set of profit milestones, plus the hard stop that goes with it.
 * <p>
 * The hard stop lives here rather than as a constant on StopLossEngine
 * because it only makes sense alongside the rungs: 20% below entry is a
 * disaster stop on an equity but a routine wiggle on an option premium,
 * so the two numbers have to move together.
 */
public final class MilestoneLadder {

    private static final double DEFAULT_HARD_STOP_PERCENT = 0.20;

    private final String name;
    private final List<Milestone> milestones;
    private final double hardStopPercent;

    public MilestoneLadder(List<Milestone> milestones) {
        this("custom", milestones, DEFAULT_HARD_STOP_PERCENT);
    }

    public MilestoneLadder(String name, List<Milestone> milestones, double hardStopPercent) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(milestones, "milestones must not be null");
        if (milestones.isEmpty()) {
            throw new IllegalArgumentException("milestones must not be empty");
        }
        if (hardStopPercent <= 0 || hardStopPercent >= 1.0) {
            throw new IllegalArgumentException("hardStopPercent must be between 0 and 1: " + hardStopPercent);
        }
        this.milestones = List.copyOf(milestones);
        this.hardStopPercent = hardStopPercent;
    }

    public String getName() {
        return name;
    }

    public double getHardStopPercent() {
        return hardStopPercent;
    }

    public static MilestoneLadder defaultLadder() {
        return equityLadder();
    }

    /**
     * Tuned for equities, where a 1% move is a real move and 20% against
     * you is a disaster stop.
     */
    public static MilestoneLadder equityLadder() {
        return new MilestoneLadder("EQUITY", List.of(
                new Milestone(0.5, null, 0.0),
                new Milestone(1.0, null, 0.0),
                new Milestone(2.0, null, 0.0),
                new Milestone(3.0, null, 0.0),
                new Milestone(5.0, Phase.PHASE_2, 0.0),
                new Milestone(8.0, null, 0.0),
                new Milestone(13.0, Phase.PHASE_3, 0.30),
                new Milestone(21.0, null, 0.50),
                new Milestone(34.0, null, 0.70),
                new Milestone(55.0, null, 0.85)
        ), 0.20);
    }

    /**
     * Tuned for option premiums, which move roughly an order of magnitude
     * further than their underlying: the same Fibonacci sequence as the
     * equity ladder, shifted up four places so it starts where that one
     * ends.
     * <p>
     * For a weekly NIFTY ATM option (delta ~0.5) these correspond to
     * underlying moves of roughly 0.34% at PHASE_2 and 0.90% at PHASE_3.
     * The hard stop is 40% rather than 20% because 20% of a premium is a
     * routine intraday wiggle and would stop out nearly every trade.
     * <p>
     * The 1.3% opening rung sits deliberately below that sequence, as an
     * early "this is working" signal rather than a profit-locking point --
     * on an ATM premium it is about a 0.02% move in the underlying, which
     * is inside the noise. It carries no phase transition and no ownership
     * lock, so it only ever notifies.
     * <p>
     * These are reasoned starting points, not values derived from data --
     * they assume ATM and a multi-day expiry. Deeper out of the money the
     * same rungs trigger on roughly half the underlying move, and on
     * expiry day gamma makes the early rungs fire in minutes.
     */
    public static MilestoneLadder optionsLadder() {
        return new MilestoneLadder("OPTIONS", List.of(
                new Milestone(1.3, null, 0.0),
                new Milestone(5.0, null, 0.0),
                new Milestone(8.0, null, 0.0),
                new Milestone(13.0, null, 0.0),
                new Milestone(21.0, Phase.PHASE_2, 0.0),
                new Milestone(34.0, null, 0.0),
                new Milestone(55.0, Phase.PHASE_3, 0.30),
                new Milestone(89.0, null, 0.50),
                new Milestone(144.0, null, 0.70),
                new Milestone(233.0, null, 0.85)
        ), 0.40);
    }

    public List<Milestone> getMilestones() {
        return milestones;
    }

    public double[] allThresholdsPercent() {
        return milestones.stream().mapToDouble(Milestone::getPercent).toArray();
    }

    public double phaseTriggerFraction(Phase targetPhase) {
        for (Milestone m : milestones) {
            if (m.getPhaseTransition() == targetPhase) {
                return m.getPercent() / 100.0;
            }
        }
        throw new IllegalArgumentException("No trigger defined for " + targetPhase);
    }

    public Map<Double, Double> ownershipLockMap() {
        LinkedHashMap<Double, Double> map = new LinkedHashMap<>();
        for (Milestone m : milestones) {
            if (m.hasOwnershipLock()) {
                map.put(m.getPercent() / 100.0, m.getOwnershipLockPercent());
            }
        }
        return Collections.unmodifiableMap(map);
    }
}
