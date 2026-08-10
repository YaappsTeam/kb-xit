package com.kbquants.simulation.report;


import com.kbquants.simulation.runner.SimulationResult;
import com.kbquants.simulation.runner.TradeMetrics;

public class JsonReportFormatter {

    public String format(SimulationResult result) {

        StringBuilder sb = new StringBuilder();
        sb.append("{ \"results\": [");

        for (int i = 0; i < result.getTradeMetricsList().size(); i++) {

            TradeMetrics m = result.getTradeMetricsList().get(i);

            sb.append("{")
                    .append("\"ownershipMode\":\"").append(m.getOwnershipMode()).append("\",")
                    .append("\"finalStopLoss\":").append(m.getFinalStopLoss()).append(",")
                    .append("\"phase\":\"").append(m.getFinalPhase()).append("\",")
                    .append("\"mfe\":").append(m.getMaxFavorableExcursion()).append(",")
                    .append("\"mae\":").append(m.getMaxAdverseExcursion()).append(",")
                    .append("\"closed\":").append(m.isClosed())
                    .append("}");

            if (i < result.getTradeMetricsList().size() - 1) {
                sb.append(",");
            }
        }

        sb.append("]}");

        return sb.toString();
    }
}
