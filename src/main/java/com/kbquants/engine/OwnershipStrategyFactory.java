package com.kbquants.engine;

import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.OwnershipMode;

public class OwnershipStrategyFactory {

    public static OwnershipStrategy create(OwnershipMode mode, StopLossEngine stopLossEngine) {
        return create(mode, stopLossEngine, MilestoneLadder.defaultLadder());
    }

    public static OwnershipStrategy create(OwnershipMode mode, StopLossEngine stopLossEngine, MilestoneLadder ladder) {
        return switch (mode) {
            case CONTINUOUS -> new ContinuousOwnershipStrategy(stopLossEngine);
            case MILESTONE -> new MilestoneOwnershipStrategy(stopLossEngine, ladder);
        };
    }
}
