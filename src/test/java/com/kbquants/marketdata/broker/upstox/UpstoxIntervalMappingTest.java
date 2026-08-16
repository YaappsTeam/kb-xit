package com.kbquants.marketdata.broker.upstox;

import com.kbquants.marketdata.model.Timeframe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UpstoxIntervalMappingTest {

    @Test
    void toInterval_allTimeframes_haveMapping() {
        // Every timeframe must map without throwing (the previous switch handled only 3).
        for (Timeframe timeframe : Timeframe.values()) {
            assertNotNull(UpstoxIntervalMapping.toInterval(timeframe),
                    "missing interval mapping for " + timeframe);
        }
    }

    @Test
    void toInterval_knownTimeframes_returnExpectedStrings() {
        assertEquals("1minute", UpstoxIntervalMapping.toInterval(Timeframe.ONE_MIN));
        assertEquals("3minute", UpstoxIntervalMapping.toInterval(Timeframe.THREE_MIN));
        assertEquals("5minute", UpstoxIntervalMapping.toInterval(Timeframe.FIVE_MIN));
        assertEquals("15minute", UpstoxIntervalMapping.toInterval(Timeframe.FIFTEEN_MIN));
        assertEquals("1hour", UpstoxIntervalMapping.toInterval(Timeframe.ONE_HOUR));
        assertEquals("1day", UpstoxIntervalMapping.toInterval(Timeframe.ONE_DAY));
    }
}
