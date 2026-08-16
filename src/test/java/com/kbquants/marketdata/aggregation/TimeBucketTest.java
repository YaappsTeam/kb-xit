package com.kbquants.marketdata.aggregation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeBucketTest {

    @Test
    void isInitialized_beforeUpdate_returnsFalse() {
        TimeBucket bucket = new TimeBucket(1000L);
        assertFalse(bucket.isInitialized());
    }

    @Test
    void update_firstUpdate_setsAllValues() {
        TimeBucket bucket = new TimeBucket(1000L);

        bucket.update(100.0, 110.0, 90.0, 105.0, 50L);

        assertTrue(bucket.isInitialized());
        assertEquals(100.0, bucket.getOpen());
        assertEquals(110.0, bucket.getHigh());
        assertEquals(90.0, bucket.getLow());
        assertEquals(105.0, bucket.getClose());
        assertEquals(50L, bucket.getVolume());
        assertEquals(1000L, bucket.getBucketStart());
    }

    @Test
    void update_subsequentUpdates_keepOpenTrackHighLowAndCloseAndAccumulateVolume() {
        TimeBucket bucket = new TimeBucket(1000L);

        bucket.update(100.0, 110.0, 90.0, 105.0, 50L);
        bucket.update(105.0, 120.0, 95.0, 115.0, 30L);
        bucket.update(115.0, 118.0, 85.0, 100.0, 20L);

        assertEquals(100.0, bucket.getOpen(), "open must remain from first update");
        assertEquals(120.0, bucket.getHigh(), "high must be the running maximum");
        assertEquals(85.0, bucket.getLow(), "low must be the running minimum");
        assertEquals(100.0, bucket.getClose(), "close must be from the latest update");
        assertEquals(100L, bucket.getVolume(), "volume must accumulate");
    }
}
