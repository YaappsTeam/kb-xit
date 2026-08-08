package com.kbquants.live;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kbquants.session.ChargesService;
import com.kbquants.session.EstimatedChargesService;
import com.kbquants.session.TradeCost;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Round-trip costs quoted by Upstox's own brokerage calculator, so
 * breakeven reflects what this account is actually charged rather than a
 * modelled approximation.
 * <p>
 * Reachable with the read-only Analytics Token and <b>without</b> a static
 * IP, despite the Analytics Token documentation listing Charges among the
 * static-IP-restricted APIs -- verified against the live endpoint. That
 * matters: the funds and margin APIs really are restricted, so this was not
 * a given.
 * <p>
 * Two calls per trade, one per side, adding roughly 100 ms to /buy. Sell
 * charges are quoted at the entry price rather than the eventual exit
 * price, which is unknown at entry; the resulting understatement is about
 * Rs 0.24 on a Rs 46.7k position, so no refinement pass is worth a third
 * round trip.
 * <p>
 * Any failure falls back to {@link EstimatedChargesService} rather than
 * propagating -- a trade with an approximate breakeven beats no trade.
 */
@Slf4j
public class UpstoxChargesService implements ChargesService {

    private static final String BROKERAGE_URL = "https://api.upstox.com/v2/charges/brokerage";
    private static final String PRODUCT_INTRADAY = "I";
    private static final int TIMEOUT_SECONDS = 10;

    private final UpstoxDataCredentials credentials;
    private final HttpClient httpClient;
    private final ChargesService fallback;
    private final Gson gson = new Gson();

    public UpstoxChargesService(UpstoxDataCredentials credentials) {
        this(credentials,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build(),
                new EstimatedChargesService());
    }

    UpstoxChargesService(UpstoxDataCredentials credentials, HttpClient httpClient, ChargesService fallback) {
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.fallback = Objects.requireNonNull(fallback, "fallback must not be null");
    }

    @Override
    public TradeCost roundTripCost(String instrumentKey, double price, int quantity) {
        return settledCost(instrumentKey, price, price, quantity);
    }

    @Override
    public TradeCost settledCost(String instrumentKey, double entryPrice, double exitPrice, int quantity) {

        OptionalDouble buy = charges(instrumentKey, entryPrice, quantity, "BUY");
        OptionalDouble sell = charges(instrumentKey, exitPrice, quantity, "SELL");

        if (buy.isEmpty() || sell.isEmpty()) {
            TradeCost estimated = fallback.settledCost(instrumentKey, entryPrice, exitPrice, quantity);
            log.warn("Falling back to modelled charges for {}: {}", instrumentKey, estimated);
            return estimated;
        }

        return new TradeCost(buy.getAsDouble(), sell.getAsDouble(), entryPrice * quantity, false);
    }

    private OptionalDouble charges(String instrumentKey, double price, int quantity, String side) {
        try {
            String url = BROKERAGE_URL
                    + "?instrument_token=" + URLEncoder.encode(instrumentKey, StandardCharsets.UTF_8)
                    + "&quantity=" + quantity
                    + "&product=" + PRODUCT_INTRADAY
                    + "&transaction_type=" + side
                    + "&price=" + price;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + credentials.getAnalyticsToken())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Upstox brokerage lookup failed for {} {}: status={} body={}",
                        instrumentKey, side, response.statusCode(), response.body());
                return OptionalDouble.empty();
            }

            return extractTotal(response.body(), gson);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OptionalDouble.empty();
        } catch (Exception e) {
            log.warn("Upstox brokerage lookup failed for {} {}: {}", instrumentKey, side, e.getMessage());
            return OptionalDouble.empty();
        }
    }

    static OptionalDouble extractTotal(String body, Gson gson) {
        JsonObject root = gson.fromJson(body, JsonObject.class);
        if (root == null || !root.has("data")) {
            return OptionalDouble.empty();
        }
        JsonObject data = root.getAsJsonObject("data");
        if (!data.has("charges")) {
            return OptionalDouble.empty();
        }
        JsonObject charges = data.getAsJsonObject("charges");
        if (!charges.has("total") || charges.get("total").isJsonNull()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(charges.get("total").getAsDouble());
    }
}
