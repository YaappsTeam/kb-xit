package com.kbquants.simulation.report;

import com.kbquants.simulation.runner.SimulationResult;
import com.kbquants.simulation.runner.TradeMetrics;

public class ConsoleReportFormatter {

    public String format(SimulationResult result) {

        StringBuilder sb = new StringBuilder();

        sb.append("==================================================================\n");
        sb.append(String.format("%-12s %-12s %-10s %-10s %-10s %-10s%n",
                "Ownership", "FinalSL", "Phase", "MFE", "MAE", "Closed"));
        sb.append("------------------------------------------------------------------\n");

        for (TradeMetrics metrics : result.getTradeMetricsList()) {
            sb.append(String.format("%-12s %-12.2f %-10s %-10.2f %-10.2f %-10s%n",
                    metrics.getOwnershipMode(),
                    metrics.getFinalStopLoss(),
                    metrics.getFinalPhase(),
                    metrics.getMaxFavorableExcursion(),
                    metrics.getMaxAdverseExcursion(),
                    metrics.isClosed()));
        }

        sb.append("==================================================================\n");

        return sb.toString();
    }
}
