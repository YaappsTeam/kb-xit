package com.kbquants.domain;

import java.util.Objects;

/**
 * Holds whichever milestone set is currently selected.
 * <p>
 * Exists because two unrelated places need the same answer: TradeMonitor,
 * when a fill arrives, and position sizing, which needs the ladder's hard
 * stop to work out how many lots put the intended amount at risk. Passing
 * the ladder itself to both would let them drift apart the moment the user
 * switches sets mid-session.
 * <p>
 * The reference is volatile because it is written from the Telegram poller
 * thread and read from wherever a fill or a /track arrives.
 */
public final class ActiveLadder {

    private volatile MilestoneLadder ladder;

    public ActiveLadder(MilestoneLadder initial) {
        this.ladder = Objects.requireNonNull(initial, "initial ladder must not be null");
    }

    public MilestoneLadder get() {
        return ladder;
    }

    public void set(MilestoneLadder ladder) {
        this.ladder = Objects.requireNonNull(ladder, "ladder must not be null");
    }
}
