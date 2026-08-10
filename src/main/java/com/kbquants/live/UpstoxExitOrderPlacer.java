package com.kbquants.live;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kbquants.domain.TradingToken;
import com.kbquants.session.ExitOrderPlacer;
import com.kbquants.session.ExitReason;
import com.kbquants.session.TradeFillEvent;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Places real SELL orders with Upstox.
 * <p>
 * This is the only class in the system that can move money, and it is
 * written around the two ways that goes wrong rather than around the happy
 * path.
 * <p>
 * <b>Selling twice.</b> A second sell for a position that has already been
 * closed does not fail harmlessly -- it opens a short, turning a finished
 * trade into a new and unmanaged one in the opposite direction. So an
 * order id is recorded before the request is sent, not after, and any
 * later attempt for the same trade is refused. Recording it afterwards
 * would leave the gap that matters: the request that succeeded but whose
 * response never arrived.
 * <p>
 * <b>Not knowing.</b> A timeout is not a rejection. The order may be
 * resting at the exchange, so the outcome is reported as uncertain rather
 * than failed, which stops the caller retrying into a double sell.
 * <p>
 * Requires a usable daily {@link TradingToken} and a registered static IP;
 * Upstox refuses order placement from anywhere else with UDAPI1221,
 * whatever the token says.
 */
@Slf4j
public class UpstoxExitOrderPlacer implements ExitOrderPlacer {

    private static final String PLACE_ORDER_URL = "https://api.upstox.com/v2/order/place";
    private static final int TIMEOUT_SECONDS = 15;

    private final TradingToken tradingToken;
    private final String product;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    /**
     * Trades an order has already been sent for. Guards against the same
     * exit being attempted twice, from a retry or a duplicate trigger.
     */
    private final Set<String> attempted = Collections.synchronizedSet(new HashSet<>());

    public UpstoxExitOrderPlacer(TradingToken tradingToken, String product) {
        this(tradingToken, product,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build());
    }

    UpstoxExitOrderPlacer(TradingToken tradingToken, String product, HttpClient httpClient) {
        this.tradingToken = Objects.requireNonNull(tradingToken, "tradingToken must not be null");
        this.product = Objects.requireNonNull(product, "product must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    @Override
    public Result placeExit(TradeFillEvent fill, double price, ExitReason reason) {

        if (!tradingToken.isUsable()) {
            return Result.failed("no usable trading token — send /token after logging in to Upstox");
        }

        if (fill.getFilledQuantity() <= 0) {
            return Result.failed("nothing to sell (quantity " + fill.getFilledQuantity() + ")");
        }

        // Claimed before the request goes out. If the response is lost, the
        // claim still stands and no second order can follow it.
        if (!attempted.add(fill.getOrderId())) {
            log.warn("Refusing a second exit order for orderId={} -- one has already been sent",
                    fill.getOrderId());
            return Result.uncertain("an exit order was already sent for this trade; "
                    + "check your broker rather than sending another");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PLACE_ORDER_URL))
                    .header("Authorization", "Bearer " + tradingToken.value())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            buildOrderBody(fill.getInstrumentKey(), fill.getFilledQuantity(), product)))
                    .build();

            log.info("Placing SELL order: instrument={} qty={} product={} reason={}",
                    fill.getInstrumentKey(), fill.getFilledQuantity(), product, reason);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return interpret(response.statusCode(), response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.uncertain("interrupted while placing the order; the order may have been sent");
        } catch (Exception e) {
            // The request may well have reached Upstox. Anything that
            // assumes otherwise risks a second sell.
            log.error("Exit order outcome unknown for {}: {}", fill.getInstrumentKey(), e.getMessage());
            return Result.uncertain("no response from Upstox (" + e.getMessage()
                    + "); the order may or may not have been placed — check your broker");
        }
    }

    /**
     * MARKET, because the decision to be out has already been taken and a
     * limit that does not fill leaves the position open with the stop
     * already breached. The cost is slippage, which on a thin option can be
     * material.
     */
    static String buildOrderBody(String instrumentKey, int quantity, String product) {
        JsonObject body = new JsonObject();
        body.addProperty("quantity", quantity);
        body.addProperty("product", product);
        body.addProperty("validity", "DAY");
        body.addProperty("price", 0);
        body.addProperty("instrument_token", instrumentKey);
        body.addProperty("order_type", "MARKET");
        body.addProperty("transaction_type", "SELL");
        body.addProperty("disclosed_quantity", 0);
        body.addProperty("trigger_price", 0);
        body.addProperty("is_amo", false);
        return body.toString();
    }

    /**
     * A 2xx with an order id is the only definite success. A 4xx is a
     * definite rejection and safe to retry. Anything else -- a 5xx, an
     * unparseable body -- leaves the outcome genuinely unknown.
     */
    Result interpret(int statusCode, String body) {

        if (statusCode >= 200 && statusCode < 300) {
            String orderId = extractOrderId(body);
            if (orderId != null) {
                log.info("Exit order accepted: brokerOrderId={}", orderId);
                return Result.placed(orderId);
            }
            return Result.uncertain("Upstox accepted the request but returned no order id: " + body);
        }

        if (statusCode >= 400 && statusCode < 500) {
            log.error("Exit order rejected ({}): {}", statusCode, body);
            return Result.failed(describeRejection(statusCode, body));
        }

        return Result.uncertain("Upstox returned " + statusCode + "; the order may have been placed: " + body);
    }

    private String extractOrderId(String body) {
        try {
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null || !root.has("data")) {
                return null;
            }
            JsonObject data = root.getAsJsonObject("data");
            return data.has("order_id") ? data.get("order_id").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The static-IP refusal is called out by name: it is the most likely
     * rejection when first going live, and the message Upstox returns does
     * not make clear that it is a deployment problem rather than a bad
     * token.
     */
    private String describeRejection(int statusCode, String body) {
        if (body != null && body.contains("UDAPI1221")) {
            return "Upstox refused the order: it only accepts orders from the static IP registered on your "
                    + "account. This is a hosting problem, not a token problem — the app has to run from that address.";
        }
        if (statusCode == 401) {
            return "Upstox rejected the token (401) — it has probably expired; send a fresh /token";
        }
        return "Upstox rejected the order (" + statusCode + "): " + body;
    }
}
