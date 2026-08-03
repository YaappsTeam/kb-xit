package com.kbquants.live;

import com.upstox.feeder.OrderUpdate;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for UpstoxOrderFillFeed's fill-detection logic (toFillEvent).
 * The real portfolio-stream-feed WebSocket connection is not exercised
 * here -- see DEVELOPMENT.md for the network-access caveat.
 */
class UpstoxOrderFillFeedTest {

    private static OrderUpdate orderUpdate(String status, String transactionType) {
        OrderUpdate update = new OrderUpdate();
        update.setStatus(status);
        update.setTransactionType(transactionType);
        update.setOrderId("order-1");
        update.setInstrumentKey("NSE_EQ|INE848E01016");
        update.setAveragePrice(123.45);
        update.setFilledQuantity(10);
        return update;
    }

    @Test
    void shouldMapCompleteBuyOrderToFillEvent() {

        Optional<TradeFillEvent> event = UpstoxOrderFillFeed.toFillEvent(orderUpdate("complete", "BUY"));

        assertTrue(event.isPresent());
        assertEquals("order-1", event.get().getOrderId());
        assertEquals("NSE_EQ|INE848E01016", event.get().getInstrumentKey());
        assertEquals(123.45, event.get().getAveragePrice());
        assertEquals(10, event.get().getFilledQuantity());
    }

    @Test
    void shouldMatchStatusAndTransactionTypeCaseInsensitively() {

        Optional<TradeFillEvent> event = UpstoxOrderFillFeed.toFillEvent(orderUpdate("COMPLETE", "buy"));

        assertTrue(event.isPresent());
    }

    @Test
    void shouldIgnoreSellOrders() {

        assertTrue(UpstoxOrderFillFeed.toFillEvent(orderUpdate("complete", "SELL")).isEmpty());
    }

    @Test
    void shouldIgnoreNonCompleteStatuses() {

        assertTrue(UpstoxOrderFillFeed.toFillEvent(orderUpdate("open", "BUY")).isEmpty());
        assertTrue(UpstoxOrderFillFeed.toFillEvent(orderUpdate("rejected", "BUY")).isEmpty());
        assertTrue(UpstoxOrderFillFeed.toFillEvent(orderUpdate("cancelled", "BUY")).isEmpty());
        assertTrue(UpstoxOrderFillFeed.toFillEvent(orderUpdate("trigger pending", "BUY")).isEmpty());
    }

    @Test
    void shouldIgnoreUpdatesWithNullStatusOrTransactionType() {

        assertTrue(UpstoxOrderFillFeed.toFillEvent(orderUpdate(null, "BUY")).isEmpty());
        assertTrue(UpstoxOrderFillFeed.toFillEvent(orderUpdate("complete", null)).isEmpty());
    }

    @Test
    void shouldThrowWhenAccessTokenIsMissing() {

        UpstoxCredentials credentials = new UpstoxCredentials("key", "secret", "https://example.com", null, false);

        assertThrows(IllegalStateException.class, () -> new UpstoxOrderFillFeed(credentials));
    }
}
