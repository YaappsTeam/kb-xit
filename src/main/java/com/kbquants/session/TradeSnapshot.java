package com.kbquants.session;

import lombok.Getter;
import lombok.Setter;

/**
 * Everything needed to resume managing one trade after a restart.
 * <p>
 * Deliberately a flat, plain-field type rather than a view over the live
 * objects: it is written to disk, so it should change only when the
 * persisted shape is meant to change, not whenever the engine's internals
 * are refactored.
 * <p>
 * The ratcheted stop and the milestone index are the two fields that
 * matter most. Without the stop, a restart would silently re-widen
 * protection that had been tightened over the life of the trade; without
 * the index, every milestone already passed would fire again.
 */
@Getter
@Setter
public class TradeSnapshot {

    private String orderId;
    private String instrumentKey;
    private String displaySymbol;
    private double entryPrice;
    private double basePrice;
    private int quantity;
    private double tickSize;

    private String ladderName;
    private String phase;
    private double currentStopLoss;
    private double lastPrice;
    private String monitorMode;

    private int nextMilestoneIndex;
    private boolean breakevenReported;

    private double buyCharges;
    private double sellCharges;
    private boolean costEstimated;
}
