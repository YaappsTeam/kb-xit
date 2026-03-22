package com.kbquants.marketdata.broker.upstox;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;

public class UpstoxHistoricalClient {

    private final HttpClient client = HttpClient.newHttpClient();
    private final String accessToken;

    public UpstoxHistoricalClient(String accessToken) {
        this.accessToken = accessToken;
    }

    public String fetch(String instrumentKey, String interval, LocalDate from, LocalDate to) throws Exception {

        String url = String.format(
                "https://api.upstox.com/v3/historical-candle/%s/%s/%s/%s",
                instrumentKey, interval, from, to);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) throw new RuntimeException("Upstox API error: " + response.body());

        return response.body();
    }
}