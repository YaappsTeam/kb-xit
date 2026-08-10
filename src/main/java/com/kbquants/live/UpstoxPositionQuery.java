package com.kbquants.live;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kbquants.domain.TradingToken;
import com.kbquants.session.BrokerPosition;
import com.kbquants.session.PositionQuery;
import com.kbquants.session.PositionQueryException;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Asks Upstox what is open, for reconciliation.
 * <p>
 * Reads today's positions. Delivery holdings from previous days are a
 * different endpoint and are not consulted: this is an intraday exit
 * engine, and a settled holding is not something it would be managing.
 * The consequence is worth knowing -- a trade left open overnight in
 * delivery moves out of positions, and reconciliation will report it as
 * gone. That is why a discrepancy stops the engine acting rather than
 * dropping the trade.
 * <p>
 * Uses the daily {@link TradingToken} rather than the year-long analytics
 * token: portfolio data is account state, and the read-only analytics
 * credential does not carry it.
 */
@Slf4j
public class UpstoxPositionQuery implements PositionQuery {

    private static final String POSITIONS_URL = "https://api.upstox.com/v2/portfolio/short-term-positions";
    private static final int TIMEOUT_SECONDS = 15;

    private final TradingToken tradingToken;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public UpstoxPositionQuery(TradingToken tradingToken) {
        this(tradingToken, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build());
    }

    UpstoxPositionQuery(TradingToken tradingToken, HttpClient httpClient) {
        this.tradingToken = Objects.requireNonNull(tradingToken, "tradingToken must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    @Override
    public boolean isAvailable() {
        return tradingToken.isUsable();
    }

    @Override
    public List<BrokerPosition> openPositions() throws PositionQueryException {

        if (!tradingToken.isUsable()) {
            throw new PositionQueryException("no usable trading token — send /token after logging in to Upstox");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(POSITIONS_URL))
                    .header("Authorization", "Bearer " + tradingToken.value())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parse(response.statusCode(), response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PositionQueryException("interrupted while asking Upstox for open positions", e);
        } catch (PositionQueryException e) {
            throw e;
        } catch (Exception e) {
            throw new PositionQueryException("could not reach Upstox: " + e.getMessage(), e);
        }
    }

    /**
     * Anything other than a 2xx with a readable body is a failure, never an
     * empty list. An error mistaken for "nothing is open" would make every
     * managed trade look closed.
     */
    List<BrokerPosition> parse(int statusCode, String body) throws PositionQueryException {

        if (statusCode < 200 || statusCode >= 300) {
            if (body != null && body.contains("UDAPI1221")) {
                throw new PositionQueryException("Upstox only serves this from the static IP registered on your "
                        + "account — a hosting problem, not a token problem");
            }
            if (statusCode == 401) {
                throw new PositionQueryException("Upstox rejected the token (401) — send a fresh /token");
            }
            throw new PositionQueryException("Upstox returned " + statusCode + ": " + body);
        }

        JsonArray data;
        try {
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null || !root.has("data") || !root.get("data").isJsonArray()) {
                throw new PositionQueryException("Upstox returned no position data: " + body);
            }
            data = root.getAsJsonArray("data");
        } catch (PositionQueryException e) {
            throw e;
        } catch (Exception e) {
            throw new PositionQueryException("could not read Upstox's reply: " + e.getMessage(), e);
        }

        List<BrokerPosition> positions = new ArrayList<>();
        for (JsonElement element : data) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            String instrumentKey = string(row, "instrument_token");
            if (instrumentKey == null) {
                log.warn("Skipping a position row with no instrument_token: {}", row);
                continue;
            }
            positions.add(new BrokerPosition(instrumentKey, integer(row, "quantity"),
                    number(row, "average_price"), string(row, "trading_symbol"), string(row, "product")));
        }

        log.info("Upstox reports {} position row(s)", positions.size());
        return positions;
    }

    private static String string(JsonObject row, String field) {
        JsonElement value = row.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static int integer(JsonObject row, String field) {
        JsonElement value = row.get(field);
        return value == null || value.isJsonNull() ? 0 : value.getAsInt();
    }

    private static double number(JsonObject row, String field) {
        JsonElement value = row.get(field);
        return value == null || value.isJsonNull() ? 0 : value.getAsDouble();
    }
}
