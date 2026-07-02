package com.kbquants.marketdata.broker.upstox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbquants.marketdata.model.Candle;
import com.kbquants.marketdata.model.Timeframe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstoxCandleParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UpstoxCandleParser parser = new UpstoxCandleParser();

    @Test
    void parse_validResponse_mapsAllFields() throws Exception {
        String json = """
                {
                  "status": "success",
                  "data": {
                    "candles": [
                      ["2021-01-01T09:15:00+05:30", 100.5, 110.0, 99.0, 105.5, 1000, 25]
                    ]
                  }
                }
                """;

        UpstoxCandleResponse response = objectMapper.readValue(json, UpstoxCandleResponse.class);
        List<Candle> candles = parser.parse(response, "NSE_EQ|TEST", Timeframe.ONE_MIN);

        assertEquals(1, candles.size());
        Candle candle = candles.get(0);
        assertEquals(100.5, candle.getOpen());
        assertEquals(110.0, candle.getHigh());
        assertEquals(99.0, candle.getLow());
        assertEquals(105.5, candle.getClose());
        assertEquals(1000L, candle.getVolume());
        assertEquals(25L, candle.getOpenInterest());
        assertEquals("NSE_EQ|TEST", candle.getSymbol());
        assertEquals(Timeframe.ONE_MIN, candle.getTimeframe());
    }

    @Test
    void parse_nullOpenInterest_mapsToNull() throws Exception {
        String json = """
                {
                  "status": "success",
                  "data": {
                    "candles": [
                      ["2021-01-01T09:15:00+05:30", 100.5, 110.0, 99.0, 105.5, 1000, null]
                    ]
                  }
                }
                """;

        UpstoxCandleResponse response = objectMapper.readValue(json, UpstoxCandleResponse.class);
        List<Candle> candles = parser.parse(response, "NSE_EQ|TEST", Timeframe.ONE_MIN);

        assertNull(candles.get(0).getOpenInterest());
    }

    @Test
    void parse_emptyCandles_returnsEmptyList() throws Exception {
        String json = """
                {
                  "status": "success",
                  "data": {
                    "candles": []
                  }
                }
                """;

        UpstoxCandleResponse response = objectMapper.readValue(json, UpstoxCandleResponse.class);
        List<Candle> candles = parser.parse(response, "NSE_EQ|TEST", Timeframe.FIVE_MIN);

        assertTrue(candles.isEmpty());
    }
}
