package com.kbquants.domain;

/**
 * The rupee loss a single trade is allowed to take if its hard stop is hit.
 * <p>
 * Mutable and shared, for the same reason {@link ActiveLadder} is: position
 * sizing reads it on every /track while the user may change it from
 * Telegram between trades. Volatile because those are different threads.
 * <p>
 * Zero means no risk ceiling -- size is then capped by capital alone, and a
 * stopped-out trade costs CAPITAL_PER_TRADE x the set's hard stop, which on
 * the OPTIONS set is 40%.
 */
public final class RiskSettings {

    private volatile double maxRiskPerTrade;

    public RiskSettings(double maxRiskPerTrade) {
        set(maxRiskPerTrade);
    }

    public double getMaxRiskPerTrade() {
        return maxRiskPerTrade;
    }

    public boolean isEnabled() {
        return maxRiskPerTrade > 0;
    }

    public void set(double maxRiskPerTrade) {
        if (maxRiskPerTrade < 0) {
            throw new IllegalArgumentException("maxRiskPerTrade must not be negative: " + maxRiskPerTrade);
        }
        this.maxRiskPerTrade = maxRiskPerTrade;
    }
}
