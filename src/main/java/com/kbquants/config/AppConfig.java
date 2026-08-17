package com.kbquants.config;

import lombok.Data;

import java.util.Map;

/**
 * YAML shape of the whole optional {@code config.yml} -- see
 * {@code config.yml.example} for every key and {@code GIT_WORKFLOW.md}-style
 * reasoning in story #22 for why each setting lives here or stays env-var-only.
 * <p>
 * Every field is nullable: an absent key means "use the existing env var or
 * hardcoded default," not "use some YAML-side default" -- see {@code Main}'s
 * config/env/default resolution.
 * <p>
 * Mutable Jackson-mapping POJO by design (matches {@code UpstoxCandleResponse}'s
 * precedent for other ported/loaded shapes); nothing outside {@link ConfigLoader}
 * should hold a reference to one.
 */
@Data
public class AppConfig {

    /** Replaces {@link com.kbquants.domain.MilestoneSets}'s registry entirely when present. */
    private Map<String, MilestoneLadderConfig> milestoneLadders;

    private String tradingMode;
    private String marketData;
    private Boolean watchBrokerFills;
    private Boolean placeRealOrders;
    private String eodExitTime;
    private String eodTimezone;
    private String instrumentRefreshTime;
    private String tradeStateDir;
    private String orderProduct;
}
