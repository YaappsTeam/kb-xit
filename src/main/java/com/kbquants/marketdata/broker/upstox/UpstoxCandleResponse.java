package com.kbquants.marketdata.broker.upstox;

import lombok.Data;

import java.util.List;

@Data
public class UpstoxCandleResponse {

    private String status;
    private DataNode data;

    @Data
    public static class DataNode {
        private List<List<Object>> candles;
    }
}