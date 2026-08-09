package com.kbquants.session;

import java.util.List;

/**
 * Asks the broker what is actually open.
 * <p>
 * It throws rather than returning an empty list when it cannot ask. The
 * distinction is the whole point of the interface: "nothing is open" and
 * "I could not find out" look identical as an empty list, and acting on
 * the first when the second is true would stop the engine watching every
 * position it holds.
 */
public interface PositionQuery {

    List<BrokerPosition> openPositions() throws PositionQueryException;

    /** Whether asking is possible at all right now -- typically, whether a token is held. */
    default boolean isAvailable() {
        return true;
    }
}
