package com.kbquants.marketdata.pricepath;

import com.kbquants.marketdata.model.Candle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts a sequence of historical candles into the plain {@code List<Double>}
 * price path that {@code SequentialCombinationExecutor} and the rest of the
 * simulation engine already consume -- the same shape {@code
 * StochasticPricePathGenerator} produces, just sourced from real candles
 * instead of a random walk.
 */
public final class CandleToPricePath {

    private CandleToPricePath() {
    }

    /**
     * The closing price of each candle, in order. Empty input yields an
     * empty path rather than throwing -- callers that need a non-empty path
     * (e.g. {@code SequentialCombinationExecutor}) already guard for that.
     */
    public static List<Double> closingPrices(List<Candle> candles) {
        Objects.requireNonNull(candles, "candles must not be null");

        List<Double> prices = new ArrayList<>(candles.size());
        for (Candle candle : candles) {
            prices.add(candle.getClose());
        }
        return prices;
    }
}
