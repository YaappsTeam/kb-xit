package com.kbquants.live;

import com.kbquants.domain.TradingToken;
import com.kbquants.session.BrokerPosition;
import com.kbquants.session.PositionQueryException;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading Upstox's position list, tested around the one mistake that
 * matters: an error must never come back as an empty list. "Nothing is
 * open" would stand every managed trade down.
 */
class UpstoxPositionQueryTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static UpstoxPositionQuery query() {
        TradingToken token = new TradingToken(IST);
        token.set("valid-token");
        return new UpstoxPositionQuery(token, HttpClient.newHttpClient());
    }

    // --- refusing to guess ---

    @Test
    void shouldReportItselfUnavailableWithoutAToken() {

        UpstoxPositionQuery query = new UpstoxPositionQuery(new TradingToken(IST), HttpClient.newHttpClient());

        assertFalse(query.isAvailable());
    }

    @Test
    void shouldThrowRatherThanReturnNothingWithoutAToken() {

        UpstoxPositionQuery query = new UpstoxPositionQuery(new TradingToken(IST), HttpClient.newHttpClient());

        PositionQueryException e = assertThrows(PositionQueryException.class, query::openPositions);
        assertTrue(e.getMessage().contains("/token"));
    }

    @Test
    void anErrorResponseShouldThrowRatherThanLookEmpty() {

        assertThrows(PositionQueryException.class, () -> query().parse(500, "server error"));
    }

    @Test
    void aBodyWithoutDataShouldThrow() {

        assertThrows(PositionQueryException.class, () -> query().parse(200, "{\"status\":\"success\"}"));
    }

    @Test
    void unreadableJsonShouldThrow() {

        assertThrows(PositionQueryException.class, () -> query().parse(200, "not json at all"));
    }

    @Test
    void shouldExplainTheStaticIpRefusalPlainly() {

        PositionQueryException e = assertThrows(PositionQueryException.class,
                () -> query().parse(401, "{\"errors\":[{\"errorCode\":\"UDAPI1221\"}]}"));

        assertTrue(e.getMessage().contains("static IP"));
    }

    @Test
    void shouldSuggestAFreshTokenOnA401() {

        PositionQueryException e = assertThrows(PositionQueryException.class,
                () -> query().parse(401, "{\"errors\":[]}"));

        assertTrue(e.getMessage().contains("/token"));
    }

    // --- reading what it says ---

    @Test
    void shouldReadAPosition() throws Exception {

        List<BrokerPosition> positions = query().parse(200, """
                {"status":"success","data":[
                  {"instrument_token":"NSE_FO|45148","quantity":65,"average_price":55.3,
                   "trading_symbol":"NIFTY 25000 CE","product":"I"}]}""");

        assertEquals(1, positions.size());
        BrokerPosition position = positions.get(0);
        assertEquals("NSE_FO|45148", position.getInstrumentKey());
        assertEquals(65, position.getQuantity());
        assertEquals(55.3, position.getAveragePrice(), 0.0001);
        assertEquals("NIFTY 25000 CE", position.getDisplaySymbol());
        assertTrue(position.isLong());
    }

    /** An account holding nothing is a real, valid answer. */
    @Test
    void anEmptyDataArrayShouldBeNoPositions() throws Exception {

        assertTrue(query().parse(200, "{\"status\":\"success\",\"data\":[]}").isEmpty());
    }

    @Test
    void shouldCarryShortsThroughForTheComparisonToDiscard() throws Exception {

        List<BrokerPosition> positions = query().parse(200,
                "{\"data\":[{\"instrument_token\":\"NSE_FO|1\",\"quantity\":-65}]}");

        assertFalse(positions.get(0).isLong());
    }

    /** A row this system cannot identify is skipped, not guessed at. */
    @Test
    void shouldSkipARowWithNoInstrument() throws Exception {

        List<BrokerPosition> positions = query().parse(200,
                "{\"data\":[{\"quantity\":65},{\"instrument_token\":\"NSE_FO|1\",\"quantity\":65}]}");

        assertEquals(1, positions.size());
    }

    @Test
    void shouldTolerateMissingOptionalFields() throws Exception {

        List<BrokerPosition> positions = query().parse(200,
                "{\"data\":[{\"instrument_token\":\"NSE_FO|1\",\"quantity\":65}]}");

        assertEquals("NSE_FO|1", positions.get(0).getDisplaySymbol());
        assertEquals(0, positions.get(0).getAveragePrice(), 0.0001);
    }
}
