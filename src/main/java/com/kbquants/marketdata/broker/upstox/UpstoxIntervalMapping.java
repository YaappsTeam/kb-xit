package com.kbquants.marketdata.broker.upstox;

import com.kbquants.marketdata.model.Timeframe;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Maps internal {@link Timeframe} values to Upstox historical-candle interval
 * strings.
 *
 * <p>Implemented as an immutable lookup map rather than a switch statement so
 * that every timeframe is covered and new timeframes fail fast with a clear
 * error instead of a silent gap (per the "no switch-case type handling" rule).
 */
public final class UpstoxIntervalMapping {

    private static final Map<Timeframe, String> INTERVAL_BY_TIMEFRAME = buildMapping();

    private UpstoxIntervalMapping() {
    }

    private static Map<Timeframe, String> buildMapping() {
        Map<Timeframe, String> mapping = new EnumMap<>(Timeframe.class);
        mapping.put(Timeframe.ONE_MIN, "1minute");
        mapping.put(Timeframe.THREE_MIN, "3minute");
        mapping.put(Timeframe.FIVE_MIN, "5minute");
        mapping.put(Timeframe.FIFTEEN_MIN, "15minute");
        mapping.put(Timeframe.ONE_HOUR, "1hour");
        mapping.put(Timeframe.ONE_DAY, "1day");
        return Collections.unmodifiableMap(mapping);
    }

    public static String toInterval(Timeframe timeframe) {
        String interval = INTERVAL_BY_TIMEFRAME.get(timeframe);
        if (interval == null) {
            throw new IllegalArgumentException("No Upstox interval mapping for timeframe: " + timeframe);
        }
        return interval;
    }
}
