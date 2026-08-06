package com.kbquants.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MilestoneLadder {

    private final List<Milestone> milestones;

    public MilestoneLadder(List<Milestone> milestones) {
        Objects.requireNonNull(milestones, "milestones must not be null");
        if (milestones.isEmpty()) {
            throw new IllegalArgumentException("milestones must not be empty");
        }
        this.milestones = List.copyOf(milestones);
    }

    public static MilestoneLadder defaultLadder() {
        return new MilestoneLadder(List.of(
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
        ));
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
