package com.kbquants.session;


import com.kbquants.domain.ExitModel;
import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.Phase;
import com.kbquants.domain.TradeContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for TradingSession.
 * <p>
 * Responsibility:
 * - Ensure price updates are delegated to ExitEngine
 * - Ensure no processing occurs after trade closure
 * - Ensure trading mode is stored correctly
 * <p>
 * Does NOT test engine internals.
 */
class TradingSessionTest {

    /**
     * TradingSession should delegate price updates
     * to ExitEngine correctly.
     */
    @Test
    void shouldDelegatePriceUpdatesToExitEngine() {

        TradeContext context = new TradeContext(
                "T1",
                100.0,
                101.0,
                1,
                ExitModel.MODERATE,
                OwnershipMode.CONTINUOUS
        );

        TradingSession session =
                new TradingSession(TradingMode.SIMULATION, context);

        // Trigger Phase 2
        session.onPrice(106.0, System.currentTimeMillis());

        assertEquals(Phase.PHASE_2, context.getCurrentPhase());
        assertEquals(101.0, context.getCurrentStopLoss());
    }

    /**
     * TradingSession must not process prices
     * after trade is marked closed.
     */
    @Test
    void shouldNotProcessPriceAfterTradeIsClosed() {

        TradeContext context = new TradeContext(
                "T1",
                100.0,
                101.0,
                1,
                ExitModel.MODERATE,
                OwnershipMode.CONTINUOUS
        );

        TradingSession session =
                new TradingSession(TradingMode.SIMULATION, context);

        context.setClosed(true);

        session.onPrice(120.0, System.currentTimeMillis());

        // Nothing should change
        assertEquals(0.0, context.getCurrentStopLoss());
        assertEquals(Phase.PHASE_1, context.getCurrentPhase());
    }

    /**
     * TradingSession should expose correct TradingMode.
     */
    @Test
    void shouldExposeCorrectTradingMode() {

        TradeContext context = new TradeContext(
                "T1",
                100.0,
                101.0,
                1,
                ExitModel.MODERATE,
                OwnershipMode.CONTINUOUS
        );

        TradingSession session =
                new TradingSession(TradingMode.PAPER, context);

        assertEquals(TradingMode.PAPER, session.getTradingMode());
    }
}
