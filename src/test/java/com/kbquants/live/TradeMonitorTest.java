package com.kbquants.live;

import com.kbquants.notification.Notifier;
import com.kbquants.session.TrackRequestResolver;
import com.kbquants.session.LiteralTrackRequestResolver;
import com.kbquants.session.MarketDataFeed;
import com.kbquants.session.OrderFillFeed;
import com.kbquants.session.PriceListener;
import com.kbquants.session.TradeFillEvent;
import com.kbquants.session.TradeFillListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for TradeMonitor using fake OrderFillFeed/MarketDataFeed
 * implementations, so no real network connection is ever attempted.
 */
class TradeMonitorTest {

    /**
     * These tests exercise TradeMonitor's trade lifecycle, not symbol
     * resolution, so they use the literal resolver: /buy arguments are
     * taken exactly as given, with no instrument master or price lookup.
     */
    private static final TrackRequestResolver RESOLVER = new LiteralTrackRequestResolver();

    private static class FakeOrderFillFeed implements OrderFillFeed {
        TradeFillListener capturedListener;

        @Override
        public void start(TradeFillListener listener) {
            this.capturedListener = listener;
        }

        @Override
        public void stop() {
        }
    }

    private static class FakeFeed implements MarketDataFeed {
        PriceListener capturedListener;

        @Override
        public void start(PriceListener listener) {
            this.capturedListener = listener;
        }
    }

    private static class RecordingNotifier implements Notifier {
        final List<String> messages = new ArrayList<>();

        @Override
        public void send(String message) {
            messages.add(message);
        }
    }

    @Test
    void shouldStartWatchingInstrumentOnFillAndNotify() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed orderFillFeed = new FakeOrderFillFeed();
        Map<String, FakeFeed> feedsByInstrument = new HashMap<>();

        new TradeMonitor(orderFillFeed,
                fill -> feedsByInstrument.computeIfAbsent(fill.getInstrumentKey(), k -> new FakeFeed()),
                notifier, RESOLVER);

        orderFillFeed.capturedListener.onFill(new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10));

        FakeFeed feed = feedsByInstrument.get("NSE_EQ|INE848E01016");
        assertTrue(feed != null && feed.capturedListener != null);
        assertEquals(1, notifier.messages.size());
        assertTrue(notifier.messages.get(0).contains("now tracking"));
    }

    @Test
    void shouldNotifyOnMilestoneCrossing() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed orderFillFeed = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();

        new TradeMonitor(orderFillFeed, fill -> feed, notifier, RESOLVER);

        // 10 @ 100 = Rs 1,000 invested. Flat brokerage dominates at that
        // size, so estimated round-trip costs are 4.23% and breakeven is
        // 104.23 -- a small position is expensive in percentage terms.
        orderFillFeed.capturedListener.onFill(new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10));
        notifier.messages.clear();

        feed.capturedListener.onPrice(105.28, 1L); // +1% net of costs

        assertTrue(notifier.messages.stream().anyMatch(m -> m.contains("1% net")),
                () -> "no net milestone in " + notifier.messages);
    }

    /**
     * The case this whole cost model exists for: a gross gain that is
     * actually a loss must not be reported as profit.
     */
    @Test
    void shouldNotReportMilestoneOnAGrossGainThatIsANetLoss() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed orderFillFeed = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();

        new TradeMonitor(orderFillFeed, fill -> feed, notifier, RESOLVER);

        orderFillFeed.capturedListener.onFill(new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10));
        notifier.messages.clear();

        feed.capturedListener.onPrice(100.5, 1L); // +0.5% gross, but costs are 4.23%

        assertTrue(notifier.messages.isEmpty(), () -> "expected silence, got " + notifier.messages);
    }

    @Test
    void shouldAnnounceBreakevenOnceCostsAreCovered() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed orderFillFeed = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();

        new TradeMonitor(orderFillFeed, fill -> feed, notifier, RESOLVER);

        orderFillFeed.capturedListener.onFill(new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10));
        notifier.messages.clear();

        feed.capturedListener.onPrice(104.23, 1L);
        feed.capturedListener.onPrice(104.30, 1L); // must not repeat

        assertEquals(1, notifier.messages.stream().filter(m -> m.contains("breakeven")).count(),
                () -> notifier.messages.toString());
    }

    @Test
    void shouldIgnoreDuplicateFillEventsForSameOrderId() {

        Map<String, Integer> feedCreationCount = new HashMap<>();
        FakeOrderFillFeed orderFillFeed = new FakeOrderFillFeed();

        new TradeMonitor(orderFillFeed,
                fill -> {
                    feedCreationCount.merge(fill.getInstrumentKey(), 1, Integer::sum);
                    return new FakeFeed();
                },
                message -> { }, RESOLVER);

        TradeFillEvent fill = new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10);
        orderFillFeed.capturedListener.onFill(fill);
        orderFillFeed.capturedListener.onFill(fill);

        assertEquals(1, feedCreationCount.get("NSE_EQ|INE848E01016"));
    }

    @Test
    void shouldCloseTradeWhenPriceHitsStopLoss() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed orderFillFeed = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();

        new TradeMonitor(orderFillFeed, fill -> feed, notifier, RESOLVER);

        orderFillFeed.capturedListener.onFill(new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10));
        notifier.messages.clear();

        // Hard safety stop-loss = 100 * 0.8 = 80. A drop to 79 must trigger exit.
        feed.capturedListener.onPrice(79.0, 1L);

        assertEquals(1, notifier.messages.size());
        assertTrue(notifier.messages.get(0).contains("stop-loss hit"));

        // Further price updates after close must produce no more notifications.
        feed.capturedListener.onPrice(150.0, 2L);
        assertEquals(1, notifier.messages.size());
    }

    @Test
    void shouldForceExitSpecificTradeViaOnExit() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed orderFillFeed = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();

        TradeMonitor monitor = new TradeMonitor(orderFillFeed, fill -> feed, notifier, RESOLVER);

        orderFillFeed.capturedListener.onFill(new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10));
        feed.capturedListener.onPrice(105.0, 1L);
        notifier.messages.clear();

        monitor.onExit("order-1");

        assertEquals(1, notifier.messages.size());
        assertTrue(notifier.messages.get(0).contains("force-exited"));

        notifier.messages.clear();
        feed.capturedListener.onPrice(200.0, 2L);
        assertTrue(notifier.messages.isEmpty());
    }

    @Test
    void shouldReportNoTradeFoundForUnknownOrderId() {

        RecordingNotifier notifier = new RecordingNotifier();
        TradeMonitor monitor = new TradeMonitor(new FakeOrderFillFeed(), fill -> new FakeFeed(), notifier, RESOLVER);

        monitor.onExit("does-not-exist");

        assertEquals(1, notifier.messages.size());
        assertTrue(notifier.messages.get(0).contains("No active trade found"));
    }

    @Test
    void shouldForceExitAllOpenTrades() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed orderFillFeed = new FakeOrderFillFeed();
        Map<String, FakeFeed> feeds = new HashMap<>();

        TradeMonitor monitor = new TradeMonitor(orderFillFeed,
                fill -> feeds.computeIfAbsent(fill.getInstrumentKey(), k -> new FakeFeed()), notifier, RESOLVER);

        orderFillFeed.capturedListener.onFill(new TradeFillEvent("order-1", "INSTR_A", 100.0, 10));
        orderFillFeed.capturedListener.onFill(new TradeFillEvent("order-2", "INSTR_B", 200.0, 5));
        notifier.messages.clear();

        monitor.onExitAll();

        assertEquals(2, notifier.messages.stream().filter(m -> m.contains("force-exited")).count());
    }

    @Test
    void shouldReportNoActiveTradesToExitAllWhenNoneAreOpen() {

        RecordingNotifier notifier = new RecordingNotifier();
        TradeMonitor monitor = new TradeMonitor(new FakeOrderFillFeed(), fill -> new FakeFeed(), notifier, RESOLVER);

        monitor.onExitAll();

        assertEquals(1, notifier.messages.size());
        assertTrue(notifier.messages.get(0).contains("No active trades"));
    }

    @Test
    void shouldCreateTradeFromTelegramBuyCommand() {

        RecordingNotifier notifier = new RecordingNotifier();
        Map<String, FakeFeed> feeds = new HashMap<>();

        TradeMonitor monitor = new TradeMonitor(new FakeOrderFillFeed(),
                fill -> feeds.computeIfAbsent(fill.getInstrumentKey(), k -> new FakeFeed()), notifier, RESOLVER);

        monitor.onTrack(List.of("NSE_EQ|INE848E01016", "1500.0", "10"));

        assertTrue(feeds.containsKey("NSE_EQ|INE848E01016"));
        assertEquals(1, notifier.messages.size());
        assertTrue(notifier.messages.get(0).contains("now tracking"));
    }

    @Test
    void shouldReportActiveTradesOnStatusRequest() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed orderFillFeed = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();

        TradeMonitor monitor = new TradeMonitor(orderFillFeed, fill -> feed, notifier, RESOLVER);

        orderFillFeed.capturedListener.onFill(new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10));
        feed.capturedListener.onPrice(102.0, 1L);
        notifier.messages.clear();

        monitor.onStatusRequested();

        assertEquals(1, notifier.messages.size());
        assertTrue(notifier.messages.get(0).contains("NSE_EQ|INE848E01016"));
        assertTrue(notifier.messages.get(0).contains("order-1"));
    }

    @Test
    void shouldReportNoActiveTradesOnStatusRequestWhenNoneOpen() {

        RecordingNotifier notifier = new RecordingNotifier();
        TradeMonitor monitor = new TradeMonitor(new FakeOrderFillFeed(), fill -> new FakeFeed(), notifier, RESOLVER);

        monitor.onStatusRequested();

        assertEquals(1, notifier.messages.size());
        assertTrue(notifier.messages.get(0).contains("No active trades"));
    }

    /**
     * A finished trade is dropped, so acting on it afterwards reports that
     * it is gone rather than force-exiting it a second time. Previously
     * closed trades stayed in the map for the life of the process and
     * /exit would happily "force-exit" one again.
     */
    @Test
    void shouldReportAnAlreadyClosedTradeAsGone() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed orderFillFeed = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();

        TradeMonitor monitor = new TradeMonitor(orderFillFeed, fill -> feed, notifier, RESOLVER);
        orderFillFeed.capturedListener.onFill(new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10));
        monitor.onExit("order-1");
        notifier.messages.clear();

        monitor.onExit("order-1");

        assertTrue(notifier.messages.get(0).contains("No active trade found"),
                () -> notifier.messages.toString());
    }

    @Test
    void closedTradesShouldNotAppearInStatus() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed orderFillFeed = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();

        TradeMonitor monitor = new TradeMonitor(orderFillFeed, fill -> feed, notifier, RESOLVER);
        orderFillFeed.capturedListener.onFill(new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10));
        monitor.onExit("order-1");
        notifier.messages.clear();

        monitor.onStatusRequested();

        assertEquals("No active trades", notifier.messages.get(0));
    }

    /**
     * Dropping the trade must not drop the duplicate-fill guard with it:
     * a replayed fill for a settled order must not re-open it.
     */
    @Test
    void shouldStillIgnoreAReplayedFillAfterTheTradeHasClosed() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed orderFillFeed = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();

        TradeMonitor monitor = new TradeMonitor(orderFillFeed, fill -> feed, notifier, RESOLVER);
        orderFillFeed.capturedListener.onFill(new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10));
        monitor.onExit("order-1");
        notifier.messages.clear();

        orderFillFeed.capturedListener.onFill(new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10));

        assertTrue(notifier.messages.isEmpty(), () -> "re-opened a settled trade: " + notifier.messages);
        monitor.onStatusRequested();
        assertEquals("No active trades", notifier.messages.get(0));
    }

    @Test
    void exitAllShouldClearEveryTrade() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed orderFillFeed = new FakeOrderFillFeed();

        TradeMonitor monitor = new TradeMonitor(orderFillFeed,
                fill -> new FakeFeed(), notifier, RESOLVER);
        orderFillFeed.capturedListener.onFill(new TradeFillEvent("order-1", "A", 100.0, 10));
        orderFillFeed.capturedListener.onFill(new TradeFillEvent("order-2", "B", 200.0, 10));
        monitor.onExitAll();
        notifier.messages.clear();

        monitor.onStatusRequested();

        assertEquals("No active trades", notifier.messages.get(0));
    }
}
