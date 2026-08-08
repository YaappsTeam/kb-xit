package com.kbquants.live;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kbquants.session.QuoteService;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Last-traded-price lookup over Upstox's V3 market-quote REST API,
 * authenticated with the read-only Analytics Token.
 * <p>
 * Called via plain HttpClient rather than the SDK's MarketQuoteApi for the
 * same reason TelegramCommandHandler does: the request is a single GET, and
 * this keeps the exact URL and Bearer header explicit and verifiable.
 * <p>
 * The response keys its data map by a symbol form ("NSE_INDEX:Nifty 50")
 * rather than the instrument key that was requested, so the value is read
 * positionally instead of by key.
 */
@Slf4j
public class UpstoxQuoteService implements QuoteService {

    private static final String LTP_URL = "https://api.upstox.com/v3/market-quote/ltp?instrument_key=";
    private static final int TIMEOUT_SECONDS = 10;

    private final UpstoxDataCredentials credentials;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public UpstoxQuoteService(UpstoxDataCredentials credentials) {
        this(credentials, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build());
    }

    UpstoxQuoteService(UpstoxDataCredentials credentials, HttpClient httpClient) {
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    @Override
    public OptionalDouble lastTradedPrice(String instrumentKey) {
        Objects.requireNonNull(instrumentKey, "instrumentKey must not be null");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(LTP_URL + URLEncoder.encode(instrumentKey, StandardCharsets.UTF_8)))
                    .header("Authorization", "Bearer " + credentials.getAnalyticsToken())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Upstox LTP lookup failed for {}: status={} body={}",
                        instrumentKey, response.statusCode(), response.body());
                return OptionalDouble.empty();
            }

            return extractLastPrice(response.body(), gson);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OptionalDouble.empty();
        } catch (Exception e) {
            log.warn("Upstox LTP lookup failed for {}: {}", instrumentKey, e.getMessage());
            return OptionalDouble.empty();
        }
    }

    static OptionalDouble extractLastPrice(String body, Gson gson) {

        JsonObject root = gson.fromJson(body, JsonObject.class);
        if (root == null || !root.has("data")) {
            return OptionalDouble.empty();
        }

        JsonObject data = root.getAsJsonObject("data");
        for (Map.Entry<String, com.google.gson.JsonElement> entry : data.entrySet()) {
            JsonObject quote = entry.getValue().getAsJsonObject();
            if (quote.has("last_price") && !quote.get("last_price").isJsonNull()) {
                return OptionalDouble.of(quote.get("last_price").getAsDouble());
            }
        }
        return OptionalDouble.empty();
    }
}
