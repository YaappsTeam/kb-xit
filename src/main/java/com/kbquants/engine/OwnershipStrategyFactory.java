package com.kbquants.engine;

import com.kbquants.domain.OwnershipMode;

public class OwnershipStrategyFactory {

    public static OwnershipStrategy create(OwnershipMode mode, StopLossEngine stopLossEngine) {

        return switch (mode) {
            case CONTINUOUS -> new ContinuousOwnershipStrategy(stopLossEngine);
            case MILESTONE -> new MilestoneOwnershipStrategy(stopLossEngine);
        };
    }
}
