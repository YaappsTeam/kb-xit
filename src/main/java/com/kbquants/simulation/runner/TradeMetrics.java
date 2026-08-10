package com.kbquants.simulation.runner;



import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.Phase;
import lombok.Getter;

/**
 * Immutable result summary for one simulation combination.
 * <p>
 * Contains structural lifecycle metrics only.
 */
@Getter
public final class TradeMetrics {

    private final OwnershipMode ownershipMode;
    private final double finalStopLoss;
    private final Phase finalPhase;

    private final double maxFavorableExcursion;
    private final double maxAdverseExcursion;

    private final boolean forceExited;
    private final boolean closed;

    public TradeMetrics(OwnershipMode ownershipMode, double finalStopLoss, Phase finalPhase,
                        double maxFavorableExcursion, double maxAdverseExcursion,
                        boolean forceExited, boolean closed) {

        this.ownershipMode = ownershipMode;
        this.finalStopLoss = finalStopLoss;
        this.finalPhase = finalPhase;
        this.maxFavorableExcursion = maxFavorableExcursion;
        this.maxAdverseExcursion = maxAdverseExcursion;
        this.forceExited = forceExited;
        this.closed = closed;
    }
}
