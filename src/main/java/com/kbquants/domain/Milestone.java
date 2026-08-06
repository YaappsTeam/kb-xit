package com.kbquants.domain;

import lombok.Getter;

@Getter
public final class Milestone {

    private final double percent;
    private final Phase phaseTransition;
    private final double ownershipLockPercent;

    public Milestone(double percent, Phase phaseTransition, double ownershipLockPercent) {
        if (percent <= 0) {
            throw new IllegalArgumentException("percent must be positive: " + percent);
        }
        if (ownershipLockPercent < 0 || ownershipLockPercent > 1.0) {
            throw new IllegalArgumentException("ownershipLockPercent must be between 0.0 and 1.0: " + ownershipLockPercent);
        }
        this.percent = percent;
        this.phaseTransition = phaseTransition;
        this.ownershipLockPercent = ownershipLockPercent;
    }

    public boolean hasPhaseTransition() {
        return phaseTransition != null;
    }

    public boolean hasOwnershipLock() {
        return ownershipLockPercent > 0.0;
    }
}
