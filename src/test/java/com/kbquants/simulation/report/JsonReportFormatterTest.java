package com.kbquants.simulation.report;


import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.Phase;
import com.kbquants.simulation.runner.SimulationResult;
import com.kbquants.simulation.runner.TradeMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for JsonReportFormatter.
 * <p>
 * Scope:
 * - Validate JSON structure presence.
 * - Validate required keys.
 * <p>
 * Layer: simulation.report
 */
class JsonReportFormatterTest {

    @Test
    @DisplayName("Should format simulation result into valid JSON structure")
    void shouldFormatSimulationResultIntoValidJsonStructure() {

        TradeMetrics metrics = createMetrics();
        SimulationResult result = new SimulationResult(List.of(metrics));

        JsonReportFormatter formatter = new JsonReportFormatter();

        String json = formatter.format(result);

        assertTrue(json.contains("\"results\""));
        assertTrue(json.contains("\"ownershipMode\""));
        assertTrue(json.contains("\"finalStopLoss\""));
        assertTrue(json.contains("\"mfe\""));
        assertTrue(json.contains("\"mae\""));
        assertTrue(json.contains("CONTINUOUS"));
    }

    private TradeMetrics createMetrics() {
        return new TradeMetrics(OwnershipMode.CONTINUOUS,
                95.0, Phase.PHASE_3, 10.0, -5.0,
                false, true);
    }
}
