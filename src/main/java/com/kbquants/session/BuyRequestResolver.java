package com.kbquants.session;

import java.util.List;

/**
 * Turns the raw arguments of a /buy command into a {@link BuyRequest}.
 * <p>
 * Implementations differ in how much they are allowed to infer: with an
 * instrument master and a price source available, a bare symbol is enough;
 * without them, everything must be stated explicitly.
 */
public interface BuyRequestResolver {

    BuyRequest resolve(List<String> args);

    /**
     * Forces the instrument master to be re-fetched, bypassing its weekly
     * schedule, and returns a message describing the outcome.
     * <p>
     * Lives here rather than on TradeMonitor because the resolver is the
     * only component that knows whether an instrument master is in use at
     * all -- resolvers without one simply say so.
     */
    default String refreshInstruments() {
        return "no instrument master in use (symbol lookup needs MARKET_DATA=live)";
    }
}
