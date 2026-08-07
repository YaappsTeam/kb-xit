package com.kbquants.live;

import com.kbquants.session.PriceListener;
import com.upstox.feeder.MarketUpdateV3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for UpstoxMarketDataFeed.
 * <p>
 * The live WebSocket connection itself is not exercised here (that requires
 * real network access and Upstox credentials). These tests cover:
 * - Constructor validation (empty instrument keys).
 * - The tick-mapping logic (dispatchUpdate) in isolation, using hand-built
 *   MarketUpdateV3 payloads instead of a real socket.
 */
class UpstoxMarketDataFeedTest {

    private static UpstoxDataCredentials credentials() {
        return new UpstoxDataCredentials("analytics-token", false);
    }

    @Test
    void shouldThrowWhenInstrumentKeysIsEmpty() {
        assertThrows(IllegalArgumentException.class, () ->
                new UpstoxMarketDataFeed(credentials(), Set.of()));
    }

    @Test
    void shouldConstructSuccessfullyWithValidInputs() {
        new UpstoxMarketDataFeed(credentials(), Set.of("NSE_EQ|INE848E01016"));
    }

    @Test
    void shouldConstructForAnIndexInstrumentKey() {
        new UpstoxMarketDataFeed(credentials(), Set.of("NSE_INDEX|Nifty 50"));
    }

    @Test
    void dispatchUpdateShouldForwardLtpAndLastTradedTimeToListener() {

        MarketUpdateV3.LTPC ltpc = new MarketUpdateV3.LTPC();
        ltpc.setLtp(123.45);
        ltpc.setLtt(1_700_000_000_000L);

        MarketUpdateV3.Feed feed = new MarketUpdateV3.Feed();
        feed.setLtpc(ltpc);

        MarketUpdateV3 update = new MarketUpdateV3();
        update.setCurrentTs(1_700_000_000_500L);
        update.setFeeds(Map.of("NSE_EQ|INE848E01016", feed));

        List<double[]> received = new ArrayList<>();
        PriceListener listener = (price, timestamp) -> received.add(new double[]{price, timestamp});

        UpstoxMarketDataFeed.dispatchUpdate(update, listener);

        assertEquals(1, received.size());
        assertEquals(123.45, received.get(0)[0]);
        assertEquals(1_700_000_000_000.0, received.get(0)[1]);
    }

    @Test
    void dispatchUpdateShouldFallBackToCurrentTsWhenLastTradedTimeMissing() {

        MarketUpdateV3.LTPC ltpc = new MarketUpdateV3.LTPC();
        ltpc.setLtp(50.0);
        // ltt left at default (0)

        MarketUpdateV3.Feed feed = new MarketUpdateV3.Feed();
        feed.setLtpc(ltpc);

        MarketUpdateV3 update = new MarketUpdateV3();
        update.setCurrentTs(1_700_000_000_500L);
        update.setFeeds(Map.of("NSE_EQ|INE848E01016", feed));

        List<double[]> received = new ArrayList<>();
        PriceListener listener = (price, timestamp) -> received.add(new double[]{price, timestamp});

        UpstoxMarketDataFeed.dispatchUpdate(update, listener);

        assertEquals(1, received.size());
        assertEquals(1_700_000_000_500.0, received.get(0)[1]);
    }

    @Test
    void dispatchUpdateShouldSkipFeedEntriesWithoutLtpc() {

        MarketUpdateV3.Feed feedWithoutLtpc = new MarketUpdateV3.Feed();
        // ltpc left null -- e.g. a FULL-mode feed entry

        MarketUpdateV3 update = new MarketUpdateV3();
        update.setCurrentTs(1_700_000_000_500L);
        update.setFeeds(Map.of("NSE_EQ|INE848E01016", feedWithoutLtpc));

        List<double[]> received = new ArrayList<>();
        PriceListener listener = (price, timestamp) -> received.add(new double[]{price, timestamp});

        UpstoxMarketDataFeed.dispatchUpdate(update, listener);

        assertTrue(received.isEmpty());
    }

    @Test
    void dispatchUpdateShouldHandleMultipleInstruments() {

        MarketUpdateV3.LTPC ltpc1 = new MarketUpdateV3.LTPC();
        ltpc1.setLtp(10.0);
        ltpc1.setLtt(100L);
        MarketUpdateV3.Feed feed1 = new MarketUpdateV3.Feed();
        feed1.setLtpc(ltpc1);

        MarketUpdateV3.LTPC ltpc2 = new MarketUpdateV3.LTPC();
        ltpc2.setLtp(20.0);
        ltpc2.setLtt(200L);
        MarketUpdateV3.Feed feed2 = new MarketUpdateV3.Feed();
        feed2.setLtpc(ltpc2);

        Map<String, MarketUpdateV3.Feed> feeds = new HashMap<>();
        feeds.put("A", feed1);
        feeds.put("B", feed2);

        MarketUpdateV3 update = new MarketUpdateV3();
        update.setCurrentTs(999L);
        update.setFeeds(feeds);

        List<double[]> received = new ArrayList<>();
        PriceListener listener = (price, timestamp) -> received.add(new double[]{price, timestamp});

        UpstoxMarketDataFeed.dispatchUpdate(update, listener);

        assertEquals(2, received.size());
    }
}
