package com.kbquants.marketdata.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeframeTest {

    @Test
    void getBucketStart_oneMinute_flooringToMinuteBoundary() {
        // 2021-01-01T00:00:30Z = 1609459230000 ms → floors to 1609459200000
        long timestamp = 1609459230000L;
        assertEquals(1609459200000L, Timeframe.ONE_MIN.getBucketStart(timestamp));
    }

    @Test
    void getBucketStart_fiveMinute_flooringToFiveMinuteBoundary() {
        // 00:07:00 floors to 00:05:00
        long timestamp = 1609459620000L; // 2021-01-01T00:07:00Z
        assertEquals(1609459500000L, Timeframe.FIVE_MIN.getBucketStart(timestamp));
    }

    @Test
    void getBucketStart_exactBoundary_returnsSameTimestamp() {
        long boundary = 1609459200000L; // exact minute boundary
        assertEquals(boundary, Timeframe.ONE_MIN.getBucketStart(boundary));
    }

    @Test
    void getDurationMillis_allTimeframes_returnExpectedDurations() {
        assertEquals(60_000L, Timeframe.ONE_MIN.getDurationMillis());
        assertEquals(3 * 60_000L, Timeframe.THREE_MIN.getDurationMillis());
        assertEquals(5 * 60_000L, Timeframe.FIVE_MIN.getDurationMillis());
        assertEquals(15 * 60_000L, Timeframe.FIFTEEN_MIN.getDurationMillis());
        assertEquals(60 * 60_000L, Timeframe.ONE_HOUR.getDurationMillis());
        assertEquals(24 * 60 * 60_000L, Timeframe.ONE_DAY.getDurationMillis());
    }

    @Test
    void isIntraday_intradayTimeframes_returnTrue() {
        assertTrue(Timeframe.ONE_MIN.isIntraday());
        assertTrue(Timeframe.ONE_HOUR.isIntraday());
    }

    @Test
    void isIntraday_dayTimeframe_returnFalse() {
        assertFalse(Timeframe.ONE_DAY.isIntraday());
    }
}
