package com.kbquants.engine;

import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;

public class PhaseManager {

    private final double phase2TriggerFraction;
    private final double phase3TriggerFraction;

    public PhaseManager() {
        this(MilestoneLadder.defaultLadder());
    }

    public PhaseManager(MilestoneLadder ladder) {
        this.phase2TriggerFraction = ladder.phaseTriggerFraction(Phase.PHASE_2);
        this.phase3TriggerFraction = ladder.phaseTriggerFraction(Phase.PHASE_3);
    }

    /**
     * Applies every transition the price qualifies for, not just the next
     * one.
     * <p>
     * A single switch advanced one phase per tick, so a price gapping
     * through both thresholds sat in PHASE_2 until another tick arrived --
     * and since ownership locking is PHASE_3-only, the lock was missed for
     * exactly the tick where a violent move made it most valuable. It also
     * contradicted PRODUCT_REQUIREMENTS F4, which requires all intermediate
     * transitions to apply.
     * <p>
     * The loop terminates because transitions only ever move forward and
     * PHASE_3 has no handler.
     */
    public void evaluatePhaseTransition(double currentPrice, TradeContext context) {

        Phase before;
        do {
            before = context.getCurrentPhase();

            switch (before) {

                case PHASE_1 -> handlePhase1(currentPrice, context);

                case PHASE_2 -> handlePhase2(currentPrice, context);

                default -> {
                }
            }
        } while (context.getCurrentPhase() != before);
    }

    /**
     * Thresholds are measured from basePrice, not entryPrice: basePrice is
     * the cost-inclusive breakeven, so "up 2%" means 2% of money actually
     * kept rather than 2% gross with brokerage and taxes still to come.
     * On a small position those costs can exceed 1.5% of capital, enough to
     * make a gross gain a net loss.
     */
    private void handlePhase1(double currentPrice, TradeContext context) {

        double triggerPrice =
                context.getBasePrice() * (1 + phase2TriggerFraction);

        if (currentPrice >= triggerPrice) {
            context.setCurrentPhase(Phase.PHASE_2);
        }
    }

    private void handlePhase2(double currentPrice, TradeContext context) {

        double triggerPrice =
                context.getBasePrice() * (1 + phase3TriggerFraction);

        if (currentPrice >= triggerPrice) {
            context.setCurrentPhase(Phase.PHASE_3);
        }
    }
}
