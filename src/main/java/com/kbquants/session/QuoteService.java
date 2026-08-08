package com.kbquants.session;

import java.util.OptionalDouble;

/**
 * A one-shot price lookup, as opposed to {@link MarketDataFeed}'s
 * continuous stream. Used to default a trade's entry price at /track time,
 * including outside market hours where the stream has nothing to deliver
 * but the last traded price is still known.
 */
public interface QuoteService {

    /**
     * Last traded price for the instrument, or empty when it cannot be
     * determined (unknown key, network failure, no trades yet).
     */
    OptionalDouble lastTradedPrice(String instrumentKey);
}
