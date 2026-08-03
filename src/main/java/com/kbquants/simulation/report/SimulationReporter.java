package com.kbquants.simulation.report;

import com.kbquants.simulation.runner.SimulationResult;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * High-level reporting orchestrator.
 * <p>
 * Converts SimulationResult into various output formats:
 * - Console
 * - JSON
 * - CSV
 * <p>
 * Layer: simulation.report
 */

@Slf4j
public class SimulationReporter {

    private final ConsoleReportFormatter consoleFormatter;
    private final JsonReportFormatter jsonFormatter;
    private final CsvReportFormatter csvFormatter;

    public SimulationReporter() {
        this.consoleFormatter = new ConsoleReportFormatter();
        this.jsonFormatter = new JsonReportFormatter();
        this.csvFormatter = new CsvReportFormatter();
    }

    public void printToConsole(SimulationResult result) {
        Objects.requireNonNull(result, "result must not be null");

        log.info("Printing simulation result to console");

        String output = consoleFormatter.format(result);
        System.out.println(output);
    }

    public String exportAsJson(SimulationResult result) {
        Objects.requireNonNull(result, "result must not be null");
        log.info("Exporting simulation result as JSON");
        return jsonFormatter.format(result);
    }

    public String exportAsCsv(SimulationResult result) {
        Objects.requireNonNull(result, "result must not be null");
        log.info("Exporting simulation result as CSV");
        return csvFormatter.format(result);
    }
}
