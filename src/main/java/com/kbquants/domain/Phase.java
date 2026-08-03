package com.kbquants.domain;

/**
 * Represents the lifecycle phase of a trade.
 * <p>
 * PHASE_1: Initial risk validation
 * PHASE_2: Base capital protection
 * PHASE_3: Profit protection
 * PHASE_4: Forced exit (EOD)
 */
public enum Phase {
    PHASE_1,
    PHASE_2,
    PHASE_3,
    PHASE_4
}
