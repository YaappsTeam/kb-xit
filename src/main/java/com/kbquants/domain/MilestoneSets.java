package com.kbquants.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The milestone sets available to choose between at runtime.
 * <p>
 * Ships with two built-in sets (below), used as-is unless {@link
 * #configure} replaces them -- {@code Main} calls it once at startup with
 * whatever {@code config.yml}'s {@code milestoneLadders} section produced
 * (story #22). With no config file, or one that doesn't mention
 * milestone ladders, these hardcoded defaults are exactly what's in
 * effect; nothing outside this class needs to know the difference.
 */
public final class MilestoneSets {

    private static volatile Map<String, MilestoneLadder> SETS = buildDefaultSets();

    private MilestoneSets() {
    }

    /**
     * Replaces the registry with externally-supplied ladders -- called
     * once at startup, before anything reads {@link #available()},
     * {@link #names()} or {@link #byName}. A null or empty map is a
     * no-op: it means the config had nothing to say about milestone
     * ladders, so the hardcoded defaults stay in effect.
     */
    public static void configure(Map<String, MilestoneLadder> ladders) {
        if (ladders == null || ladders.isEmpty()) {
            return;
        }
        SETS = Map.copyOf(ladders);
    }

    /**
     * Test-support only: restores the hardcoded defaults after a test
     * calls {@link #configure}. This registry is process-global mutable
     * state (CODING_STANDARDS.md §9 forbids tests leaving shared mutable
     * state behind for the next one), so any test that configures it must
     * call this in an {@code @AfterEach}.
     */
    static void resetToDefaultsForTests() {
        SETS = buildDefaultSets();
    }

    private static Map<String, MilestoneLadder> buildDefaultSets() {
        LinkedHashMap<String, MilestoneLadder> sets = new LinkedHashMap<>();
        for (MilestoneLadder ladder : List.of(MilestoneLadder.equityLadder(), MilestoneLadder.optionsLadder())) {
            sets.put(ladder.getName(), ladder);
        }
        return sets;
    }

    /** In presentation order -- this is what gets offered to the user. */
    public static List<MilestoneLadder> available() {
        return List.copyOf(SETS.values());
    }

    public static List<String> names() {
        return List.copyOf(SETS.keySet());
    }

    public static Optional<MilestoneLadder> byName(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(SETS.get(name.trim().toUpperCase()));
    }

    /**
     * One-line summary for showing a set to the user: rung count, first
     * and last threshold, and the hard stop that comes with it.
     */
    public static String describe(MilestoneLadder ladder) {
        double[] thresholds = ladder.allThresholdsPercent();
        return String.format("%s — %d rungs, %s%% to %s%%, hard stop %s%%",
                ladder.getName(), thresholds.length, trim(thresholds[0]), trim(thresholds[thresholds.length - 1]),
                trim(ladder.getHardStopPercent() * 100));
    }

    /**
     * Fractional rungs are real (0.5%, 1.3%), so rounding to whole numbers
     * would print them as "0%" and "1%". Show the decimal only when there
     * is one.
     */
    static String trim(double percent) {
        return percent == Math.rint(percent)
                ? String.valueOf((long) percent)
                : String.valueOf(percent);
    }
}
