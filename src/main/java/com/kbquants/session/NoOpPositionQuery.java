package com.kbquants.session;

import java.util.List;

/**
 * Used when there is no broker to ask -- paper mode, or live data without
 * order credentials.
 * <p>
 * Reports itself unavailable and throws if asked anyway, rather than
 * returning an empty list. An empty list would read as "the broker holds
 * nothing", which would flag every open trade as gone.
 */
public class NoOpPositionQuery implements PositionQuery {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public List<BrokerPosition> openPositions() throws PositionQueryException {
        throw new PositionQueryException("no broker connection is configured to check positions against");
    }
}
