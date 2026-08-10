package com.kbquants.live;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kbquants.domain.TradingToken;
import com.kbquants.session.ExitOrderPlacer;
import com.kbquants.session.ExitReason;
import com.kbquants.session.TradeFillEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only class that can move money, tested around the ways that goes
 * wrong rather than the happy path.
 * <p>
 * No real HTTP: response interpretation and the duplicate guard are the
 * behaviour worth pinning, and neither needs a network.
 */
class UpstoxExitOrderPlacerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static TradingToken usableToken() {
        TradingToken token = new TradingToken(IST);
        token.set("valid-token");
        return token;
    }

    private static UpstoxExitOrderPlacer placer(TradingToken token) {
        return new UpstoxExitOrderPlacer(token, "I", HttpClient.newHttpClient());
    }

    private static TradeFillEvent fill(String orderId, int quantity) {
        return new TradeFillEvent(orderId, "NSE_FO|45148", 55.30, quantity, 0.05, "NIFTY 25000 CE");
    }

    // --- refusing to act without the prerequisites ---

    @Test
    void shouldRefuseWithoutAUsableToken() {

        ExitOrderPlacer.Result result = placer(new TradingToken(IST))
                .placeExit(fill("order-1", 65), 55.30, ExitReason.STOP_LOSS);

        assertFalse(result.isSuccessful());
        assertFalse(result.isUncertain(), "no token is a definite refusal, not an unknown outcome");
        assertTrue(result.getDetail().contains("/token"));
    }

    @Test
    void shouldRefuseToSellNothing() {

        ExitOrderPlacer.Result result = placer(usableToken())
                .placeExit(fill("order-1", 0), 55.30, ExitReason.MANUAL);

        assertFalse(result.isSuccessful());
    }

    // --- the duplicate guard ---

    /**
     * A second sell for a closed position does not fail harmlessly: it
     * opens a short. The claim is taken before the request goes out, so
     * even a lost response cannot be followed by another order.
     */
    @Test
    void shouldRefuseASecondOrderForTheSameTrade() {

        UpstoxExitOrderPlacer placer = placer(usableToken());
        TradeFillEvent fill = fill("order-1", 65);

        // The first attempt reaches the network and fails there; what
        // matters is that the trade is now claimed.
        placer.placeExit(fill, 55.30, ExitReason.STOP_LOSS);

        ExitOrderPlacer.Result second = placer.placeExit(fill, 55.30, ExitReason.STOP_LOSS);

        assertFalse(second.isSuccessful());
        assertTrue(second.isUncertain(), "a refused duplicate must not invite a retry");
        assertTrue(second.getDetail().contains("already sent"));
    }

    @Test
    void shouldStillAllowOrdersForOtherTrades() {

        UpstoxExitOrderPlacer placer = placer(usableToken());
        placer.placeExit(fill("order-1", 65), 55.30, ExitReason.STOP_LOSS);

        ExitOrderPlacer.Result other = placer.placeExit(fill("order-2", 65), 55.30, ExitReason.STOP_LOSS);

        assertFalse(other.getDetail().contains("already sent"));
    }

    // --- interpreting what Upstox says ---

    @Test
    void shouldTreatAnOrderIdAsSuccess() {

        ExitOrderPlacer.Result result = placer(usableToken())
                .interpret(200, "{\"status\":\"success\",\"data\":{\"order_id\":\"241010-1\"}}");

        assertTrue(result.isSuccessful());
        assertEquals("241010-1", result.getBrokerOrderId());
    }

    /** Success without an id means we cannot identify what was placed. */
    @Test
    void shouldTreatSuccessWithoutAnOrderIdAsUncertain() {

        ExitOrderPlacer.Result result = placer(usableToken()).interpret(200, "{\"status\":\"success\"}");

        assertTrue(result.isUncertain());
    }

    @Test
    void shouldTreatA4xxAsADefiniteRejection() {

        ExitOrderPlacer.Result result = placer(usableToken())
                .interpret(400, "{\"errors\":[{\"errorCode\":\"UDAPI1010\"}]}");

        assertFalse(result.isSuccessful());
        assertFalse(result.isUncertain(), "a rejection is safe to retry; it must not be flagged unknown");
    }

    /**
     * A 5xx may well have been processed. Retrying it could double-sell.
     */
    @Test
    void shouldTreatA5xxAsUncertain() {

        ExitOrderPlacer.Result result = placer(usableToken()).interpret(502, "bad gateway");

        assertTrue(result.isUncertain());
    }

    /**
     * The likeliest rejection when first going live, and Upstox's own
     * message does not make clear it is a hosting problem.
     */
    @Test
    void shouldExplainTheStaticIpRefusalPlainly() {

        ExitOrderPlacer.Result result = placer(usableToken())
                .interpret(401, "{\"errors\":[{\"errorCode\":\"UDAPI1221\"}]}");

        assertFalse(result.isSuccessful());
        assertTrue(result.getDetail().contains("static IP"));
        assertTrue(result.getDetail().contains("hosting problem"));
    }

    @Test
    void shouldSuggestAFreshTokenOnA401() {

        ExitOrderPlacer.Result result = placer(usableToken()).interpret(401, "{\"errors\":[]}");

        assertTrue(result.getDetail().contains("/token"));
    }

    // --- the order itself ---

    @Test
    void shouldBuildASellOrderForTheQuantityHeld() {

        JsonObject body = new Gson().fromJson(
                UpstoxExitOrderPlacer.buildOrderBody("NSE_FO|45148", 845, "I"), JsonObject.class);

        assertEquals("SELL", body.get("transaction_type").getAsString());
        assertEquals(845, body.get("quantity").getAsInt());
        assertEquals("NSE_FO|45148", body.get("instrument_token").getAsString());
        assertEquals("I", body.get("product").getAsString());
        assertEquals("DAY", body.get("validity").getAsString());
        assertFalse(body.get("is_amo").getAsBoolean());
    }

    /**
     * MARKET, because the decision to be out has already been taken and a
     * limit that does not fill leaves the position open with the stop
     * already breached.
     */
    @Test
    void shouldPlaceAtMarket() {

        JsonObject body = new Gson().fromJson(
                UpstoxExitOrderPlacer.buildOrderBody("NSE_FO|45148", 65, "I"), JsonObject.class);

        assertEquals("MARKET", body.get("order_type").getAsString());
        assertEquals(0, body.get("price").getAsInt());
    }

    @Test
    void shouldRespectTheConfiguredProduct() {

        JsonObject body = new Gson().fromJson(
                UpstoxExitOrderPlacer.buildOrderBody("NSE_EQ|X", 10, "D"), JsonObject.class);

        assertEquals("D", body.get("product").getAsString());
        assertNotNull(body.get("validity"));
    }
}
