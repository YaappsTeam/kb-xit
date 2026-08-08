package com.kbquants.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The milestone sets available to choose between at runtime.
 * <p>
 * Deliberately a fixed, in-code registry rather than configuration: the
 * numbers encode trading intent that wants reviewing in a diff, not
 * editing in a properties file between trades. Which set is <i>active</i>
 * is a runtime choice; what the sets contain is not.
 */
public final class MilestoneSets {

    private static final Map<String, MilestoneLadder> SETS = buildSets();

    private MilestoneSets() {
    }

    private static Map<String, MilestoneLadder> buildSets() {
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
