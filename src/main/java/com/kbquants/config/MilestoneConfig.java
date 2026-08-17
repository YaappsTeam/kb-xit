package com.kbquants.config;

import com.kbquants.domain.Phase;
import lombok.Data;

/**
 * YAML shape of one milestone rung -- see {@code config.yml.example}.
 * Mutable Jackson-mapping POJO; {@link ConfigLoader} converts it into the
 * immutable {@link com.kbquants.domain.Milestone} the rest of the app uses.
 */
@Data
public class MilestoneConfig {

    private double percent;
    private Phase phaseTransition;
    private double ownershipLockPercent;
}
