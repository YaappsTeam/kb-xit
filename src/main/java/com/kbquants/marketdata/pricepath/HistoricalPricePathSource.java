package com.kbquants.marketdata.pricepath;

import com.kbquants.instrument.InstrumentCatalog;
import com.kbquants.marketdata.feed.HistoricalCandleFeed;
import com.kbquants.marketdata.model.Candle;
import com.kbquants.marketdata.model.Timeframe;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Fetches a real historical price path for one instrument, for backtesting
 * the exit engine against actual past prices instead of a synthetic random
 * walk.
 * <p>
 * Scope is enforced here, not in {@link HistoricalCandleFeed} itself: the
 * feed stays a generic Upstox client (any instrument key), and this class --
 * the orchestration seam, one level up -- refuses instrument keys outside
 * today's tracked NIFTY-options window. Same split as {@code
 * TradeMonitor.isOutOfScope} for live fills (PRODUCT_REQUIREMENTS.md F9):
 * the broker client doesn't know about product scope, the orchestrator does.
 */
public final class HistoricalPricePathSource {

    private final HistoricalCandleFeed candleFeed;
    private final Optional<InstrumentCatalog> instrumentCatalog;

    public HistoricalPricePathSource(HistoricalCandleFeed candleFeed, Optional<InstrumentCatalog> instrumentCatalog) {
        this.candleFeed = Objects.requireNonNull(candleFeed, "candleFeed must not be null");
        this.instrumentCatalog = Objects.requireNonNull(instrumentCatalog, "instrumentCatalog must not be null");
    }

    /**
     * The closing-price path for {@code instrumentKey} over {@code [from, to]}.
     *
     * @throws IllegalArgumentException if a catalog is present and does not
     *                                  recognize {@code instrumentKey} as
     *                                  within today's tracked NIFTY-options
     *                                  scope
     */
    public List<Double> fetch(String instrumentKey, Timeframe timeframe, LocalDate from, LocalDate to) {

        if (isOutOfScope(instrumentKey)) {
            throw new IllegalArgumentException(
                    "Instrument key is outside today's tracked NIFTY-options scope: " + instrumentKey);
        }

        List<Candle> candles = candleFeed.getHistoricalCandles(instrumentKey, timeframe, from, to);
        return CandleToPricePath.closingPrices(candles);
    }

    private boolean isOutOfScope(String instrumentKey) {
        return instrumentCatalog
                .map(catalog -> catalog.registry().resolve(instrumentKey).isEmpty())
                .orElse(false);
    }
}
