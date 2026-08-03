package com.kbquants.simulation.runner;


import com.kbquants.domain.ExitModel;
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

    private final ExitModel exitModel;
    private final OwnershipMode ownershipMode;
    private final double finalStopLoss;
    private final Phase finalPhase;

    private final double maxFavorableExcursion;
    private final double maxAdverseExcursion;

    private final boolean ownershipActivated;
    private final boolean hybridActivated;
    private final boolean forceExited;
    private final boolean closed;

    public TradeMetrics(ExitModel exitModel, OwnershipMode ownershipMode, double finalStopLoss, Phase finalPhase,
                        double maxFavorableExcursion, double maxAdverseExcursion, boolean ownershipActivated, boolean hybridActivated,
                        boolean forceExited, boolean closed) {

        this.exitModel = exitModel;
        this.ownershipMode = ownershipMode;
        this.finalStopLoss = finalStopLoss;
        this.finalPhase = finalPhase;
        this.maxFavorableExcursion = maxFavorableExcursion;
        this.maxAdverseExcursion = maxAdverseExcursion;
        this.ownershipActivated = ownershipActivated;
        this.hybridActivated = hybridActivated;
        this.forceExited = forceExited;
        this.closed = closed;
    }
}
