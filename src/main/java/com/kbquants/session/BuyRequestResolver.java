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
}
