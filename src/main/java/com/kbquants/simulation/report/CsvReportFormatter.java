package com.kbquants.simulation.report;


import com.kbquants.simulation.runner.SimulationResult;
import com.kbquants.simulation.runner.TradeMetrics;

public class CsvReportFormatter {

    public String format(SimulationResult result) {

        StringBuilder sb = new StringBuilder();

        sb.append("OwnershipMode,FinalSL,Phase,MFE,MAE,Closed\n");

        for (TradeMetrics m : result.getTradeMetricsList()) {

            sb                    .append(m.getOwnershipMode()).append(",")
                    .append(m.getFinalStopLoss()).append(",")
                    .append(m.getFinalPhase()).append(",")
                    .append(m.getMaxFavorableExcursion()).append(",")
                    .append(m.getMaxAdverseExcursion()).append(",")
                    .append(m.isClosed())
                    .append("\n");
        }

        return sb.toString();
    }
}
