package com.kbquants.marketdata.pricepath;

import com.kbquants.marketdata.model.Candle;
import com.kbquants.marketdata.model.Timeframe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandleToPricePathTest {

    private static Candle candle(double close) {
        return Candle.builder()
                .timestamp(0L)
                .open(close)
                .high(close)
                .low(close)
                .close(close)
                .volume(0L)
                .symbol("NSE_FO|TEST")
                .timeframe(Timeframe.ONE_MIN)
                .build();
    }

    @Test
    void mapsEachCandleToItsClosingPriceInOrder() {

        List<Candle> candles = List.of(candle(100.0), candle(105.5), candle(98.25));

        List<Double> path = CandleToPricePath.closingPrices(candles);

        assertEquals(List.of(100.0, 105.5, 98.25), path);
    }

    @Test
    void emptyInputYieldsEmptyPathNotAnException() {

        List<Double> path = CandleToPricePath.closingPrices(List.of());

        assertTrue(path.isEmpty());
    }

    @Test
    void nullInputIsRejected() {
        assertThrows(NullPointerException.class, () -> CandleToPricePath.closingPrices(null));
    }
}
