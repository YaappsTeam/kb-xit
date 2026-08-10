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
 * Unit tests for CsvReportFormatter.
 * <p>
 * Scope:
 * - Validate header correctness.
 * - Validate row generation.
 * <p>
 * Layer: simulation.report
 */
class CsvReportFormatterTest {

    @Test
    @DisplayName("Should format simulation result into valid CSV format")
    void shouldFormatSimulationResultIntoValidCsvFormat() {

        TradeMetrics metrics = createMetrics();
        SimulationResult result = new SimulationResult(List.of(metrics));

        CsvReportFormatter formatter = new CsvReportFormatter();

        String csv = formatter.format(result);

        assertTrue(csv.startsWith("OwnershipMode,"));
        assertTrue(csv.contains("CONTINUOUS"));
        assertTrue(csv.contains("95.0"));
    }

    private TradeMetrics createMetrics() {
        return new TradeMetrics(OwnershipMode.CONTINUOUS,
                95.0, Phase.PHASE_3, 10.0, -5.0,
                false, true);
    }
}
