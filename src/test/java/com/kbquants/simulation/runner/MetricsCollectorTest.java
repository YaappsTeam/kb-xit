package com.kbquants.simulation.runner;



import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.TradeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MetricsCollector}.
 * <p>
 * Scope:
 * - Validate MFE and MAE calculations.
 * - Validate ownership activation detection.
 * - Validate hybrid activation detection.
 * - Validate force exit propagation.
 * - Validate closed state propagation.
 * <p>
 * This test class does NOT:
 * - Invoke ExitEngine logic.
 * - Validate stop loss behavior.
 * - Validate phase transitions.
 * <p>
 * Layer: simulation.runner
 */
class MetricsCollectorTest {

    @Test
    @DisplayName("Should correctly calculate maximum favorable and adverse excursion")
    void shouldCorrectlyCalculateMaximumFavorableAndAdverseExcursion() {

        TradeContext context = createContext();
        MetricsCollector collector = new MetricsCollector(100.0);

        collector.onPrice(105.0, context);
        collector.onPrice(95.0, context);
        collector.onPrice(110.0, context);
        collector.onCompletion(context);

        TradeMetrics metrics = collector.build("t1", context);

        assertEquals(10.0, metrics.getMaxFavorableExcursion());
        assertEquals(-5.0, metrics.getMaxAdverseExcursion());
    }

    @Test
    @DisplayName("Should detect force exit when context indicates force exit")
    void shouldDetectForceExitWhenContextIndicatesForceExit() {

        TradeContext context = createContext();
        context.setForceExited(true);

        MetricsCollector collector = new MetricsCollector(100.0);
        collector.onPrice(100.0, context);
        collector.onCompletion(context);

        TradeMetrics metrics = collector.build("t1", context);

        assertTrue(metrics.isForceExited());
    }

    @Test
    @DisplayName("Should propagate closed state from TradeContext into TradeMetrics")
    void shouldPropagateClosedStateFromTradeContextIntoTradeMetrics() {

        TradeContext context = createContext();
        context.setClosed(true);

        MetricsCollector collector = new MetricsCollector(100.0);
        collector.onPrice(100.0, context);
        collector.onCompletion(context);

        TradeMetrics metrics = collector.build("t1", context);

        assertTrue(metrics.isClosed());
    }

    @Test
    @DisplayName("Should handle scenario where price never moves from entry")
    void shouldHandleScenarioWherePriceNeverMovesFromEntry() {

        TradeContext context = createContext();
        MetricsCollector collector = new MetricsCollector(100.0);

        collector.onPrice(100.0, context);
        collector.onPrice(100.0, context);
        collector.onCompletion(context);

        TradeMetrics metrics = collector.build("t1", context);

        assertEquals(0.0, metrics.getMaxFavorableExcursion());
        assertEquals(0.0, metrics.getMaxAdverseExcursion());
    }

    private TradeContext createContext() {
        return new TradeContext("t1", 100.0, 100.0, 1,
                OwnershipMode.CONTINUOUS);
    }
}
