package com.kbquants.session;



import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests deterministic simulation feed behavior.
 */
class DeterministicSimulationFeedTest {

    @Test
    void shouldReplayPriceSeriesThroughTradingSession() {

        TradeContext context = new TradeContext(
                "T1",
                100.0,
                101.0,
                1,
                OwnershipMode.CONTINUOUS
        );

        TradingSession session = new TradingSession(TradingMode.SIMULATION, context);

        List<Double> prices = List.of(
                100.0,
                106.0,  // Phase 2
                113.0,  // Phase 3
                120.0   // Ownership applied
        );

        DeterministicSimulationFeed feed = new DeterministicSimulationFeed(prices);

        feed.start(session);

        assertEquals(Phase.PHASE_3, context.getCurrentPhase());
        assertTrue(context.getCurrentStopLoss() > 101.0);
    }
}
