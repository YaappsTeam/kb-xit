package com.kbquants.config;

import lombok.Data;

import java.util.List;

/**
 * YAML shape of one named milestone ladder -- see {@code config.yml.example}.
 * Mutable Jackson-mapping POJO; {@link ConfigLoader} converts it into the
 * immutable {@link com.kbquants.domain.MilestoneLadder} the rest of the app
 * uses.
 */
@Data
public class MilestoneLadderConfig {

    private double hardStopPercent;
    private List<MilestoneConfig> rungs;
}
