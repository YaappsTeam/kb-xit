package com.kbquants.marketdata.broker.upstox;

import com.kbquants.marketdata.config.MarketDataConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;

/**
 * Thin HTTP client for the Upstox historical-candle REST endpoint.
 *
 * <p>The base URL and access token are supplied via {@link MarketDataConfig} so
 * that no broker endpoint is hardcoded in the engine.
 */
public class UpstoxHistoricalClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxHistoricalClient.class);

    private final HttpClient httpClient;
    private final MarketDataConfig config;

    public UpstoxHistoricalClient(MarketDataConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
    }

    public String fetchHistoricalCandles(String instrumentKey, String interval, LocalDate from, LocalDate to)
            throws Exception {

        String url = String.format(
                "%s/historical-candle/%s/%s/%s/%s",
                config.getUpstoxBaseUrl(), instrumentKey, interval, from, to);

        log.debug("Fetching Upstox historical candles instrument={} interval={} from={} to={}",
                instrumentKey, interval, from, to);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.getUpstoxAccessToken())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("Upstox API error instrument={} status={} body={}",
                    instrumentKey, response.statusCode(), response.body());
            throw new IllegalStateException("Upstox API error: status=" + response.statusCode()
                    + " body=" + response.body());
        }

        return response.body();
    }
}
