package com.kbquants.simulation.report;


import com.kbquants.domain.ExitModel;
import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.Phase;
import com.kbquants.simulation.runner.SimulationResult;
import com.kbquants.simulation.runner.TradeMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for ConsoleReportFormatter.
 * <p>
 * Scope:
 * - Validate formatted table structure.
 * - Ensure required headers are present.
 * - Ensure formatted rows contain metric values.
 * <p>
 * Layer: simulation.report
 */
class ConsoleReportFormatterTest {

    @Test
    @DisplayName("Should format simulation result into structured console table")
    void shouldFormatSimulationResultIntoStructuredConsoleTable() {

        TradeMetrics metrics = createMetrics();
        SimulationResult result = new SimulationResult(List.of(metrics));

        ConsoleReportFormatter formatter = new ConsoleReportFormatter();

        String output = formatter.format(result);

        assertTrue(output.contains("ExitModel"));
        assertTrue(output.contains("Ownership"));
        assertTrue(output.contains("FinalSL"));
        assertTrue(output.contains("MFE"));
        assertTrue(output.contains("MAE"));
        assertTrue(output.contains("MODERATE"));
        assertTrue(output.contains("CONTINUOUS"));
    }

    private TradeMetrics createMetrics() {
        return new TradeMetrics(ExitModel.MODERATE, OwnershipMode.CONTINUOUS,
                95.0, Phase.PHASE_3, 10.0, -5.0,
                true, false, false, true);
    }
}
