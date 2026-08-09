package com.kbquants.simulation.report;



import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.Phase;
import com.kbquants.simulation.runner.SimulationResult;
import com.kbquants.simulation.runner.TradeMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SimulationReporter.
 * <p>
 * Scope:
 * - Validate null safety.
 * - Validate JSON and CSV delegation.
 * <p>
 * Layer: simulation.report
 */
class SimulationReporterTest {

    @Test
    @DisplayName("Should throw NullPointerException when result is null for console")
    void shouldThrowExceptionWhenResultIsNullForConsole() {

        SimulationReporter reporter = new SimulationReporter();

        assertThrows(NullPointerException.class,
                () -> reporter.printToConsole(null));
    }

    @Test
    @DisplayName("Should export JSON correctly")
    void shouldExportJsonCorrectly() {

        SimulationReporter reporter = new SimulationReporter();
        SimulationResult result = createResult();

        String json = reporter.exportAsJson(result);

        assertNotNull(json);
        assertTrue(json.contains("\"results\""));
    }

    @Test
    @DisplayName("Should export CSV correctly")
    void shouldExportCsvCorrectly() {

        SimulationReporter reporter = new SimulationReporter();
        SimulationResult result = createResult();

        String csv = reporter.exportAsCsv(result);

        assertNotNull(csv);
        assertTrue(csv.contains("OwnershipMode"));
    }

    private SimulationResult createResult() {
        TradeMetrics metrics = new TradeMetrics(OwnershipMode.CONTINUOUS,
                95.0, Phase.PHASE_3, 10.0, -5.0,
                false, true);
        return new SimulationResult(List.of(metrics));
    }
}
