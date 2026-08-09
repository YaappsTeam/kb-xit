package com.kbquants.live;

import com.kbquants.domain.MonitorMode;
import com.kbquants.notification.Notifier;
import com.kbquants.session.LiteralTrackRequestResolver;
import com.kbquants.session.MarketDataFeed;
import com.kbquants.session.OrderFillFeed;
import com.kbquants.session.PriceListener;
import com.kbquants.session.TradeFillEvent;
import com.kbquants.session.TradeFillListener;
import com.kbquants.session.TrackRequestResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feed lifecycle and failure reporting.
 * <p>
 * Two problems this covers. A feed that is never stopped leaves a
 * WebSocket open for every trade ever taken, for the life of the process.
 * And a feed that dies silently leaves the stop-loss unenforced while
 * everything still looks healthy -- prices simply stop arriving.
 */
class TradeMonitorFeedLifecycleTest {

    private static final TrackRequestResolver RESOLVER = new LiteralTrackRequestResolver();

    private static final class FakeOrderFillFeed implements OrderFillFeed {
        TradeFillListener listener;

        @Override
        public void start(TradeFillListener listener) {
            this.listener = listener;
        }

        @Override
        public void stop() {
        }
    }

    private static final class FakeFeed implements MarketDataFeed {
        PriceListener listener;
        Consumer<String> failureListener;
        int startCount;
        int stopCount;

        @Override
        public void start(PriceListener listener) {
            this.listener = listener;
            startCount++;
        }

        @Override
        public void stop() {
            stopCount++;
        }

        @Override
        public void setFailureListener(Consumer<String> onFailure) {
            this.failureListener = onFailure;
        }

        void fail(String reason) {
            failureListener.accept(reason);
        }
    }

    private static final class RecordingNotifier implements Notifier {
        final List<String> messages = new ArrayList<>();

        @Override
        public void send(String message) {
            messages.add(message);
        }

        boolean anyContains(String fragment) {
            return messages.stream().anyMatch(m -> m.contains(fragment));
        }
    }

    private record Fixture(TradeMonitor monitor, FakeOrderFillFeed fills, FakeFeed feed, RecordingNotifier notifier) {}

    private static Fixture fixture() {
        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();
        TradeMonitor monitor = new TradeMonitor(fills, fill -> feed, notifier, RESOLVER);
        return new Fixture(monitor, fills, feed, notifier);
    }

    private static void open(Fixture f) {
        f.fills().listener.onFill(new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10));
    }

    // --- lifecycle ---

    @Test
    void shouldCloseTheFeedWhenTheStopIsHit() {

        Fixture f = fixture();
        open(f);
        assertEquals(0, f.feed().stopCount);

        f.feed().listener.onPrice(10.0, 1L);

        assertEquals(1, f.feed().stopCount, "a finished trade must not leave its feed open");
    }

    @Test
    void shouldCloseTheFeedOnForceExit() {

        Fixture f = fixture();
        open(f);

        f.monitor().onExit("order-1");

        assertEquals(1, f.feed().stopCount);
    }

    @Test
    void shouldCloseEveryFeedOnExitAll() {

        Fixture f = fixture();
        open(f);

        f.monitor().onExitAll();

        assertEquals(1, f.feed().stopCount);
    }

    /** Releasing means stop watching, so the connection goes too. */
    @Test
    void shouldCloseTheFeedOnRelease() {

        Fixture f = fixture();
        open(f);

        f.monitor().onMonitorModeRequested("order-1", MonitorMode.RELEASED);

        assertEquals(1, f.feed().stopCount);
    }

    /**
     * ...and returning to a watching mode has to bring it back, or the
     * trade would sit there receiving nothing.
     */
    @Test
    void shouldReopenTheFeedWhenTakingATradeBack() {

        Fixture f = fixture();
        open(f);
        assertEquals(1, f.feed().startCount);

        f.monitor().onMonitorModeRequested("order-1", MonitorMode.RELEASED);
        f.monitor().onMonitorModeRequested("order-1", MonitorMode.MANAGED);

        assertEquals(2, f.feed().startCount, "feed should have been restarted");
    }

    @Test
    void observingShouldKeepTheFeedOpen() {

        Fixture f = fixture();
        open(f);

        f.monitor().onMonitorModeRequested("order-1", MonitorMode.OBSERVED);

        assertEquals(0, f.feed().stopCount, "observing still needs prices");
    }

    // --- failure reporting ---

    @Test
    void shouldReportAFeedFailureAndNameTheUnenforcedStop() {

        Fixture f = fixture();
        open(f);
        f.feed().listener.onPrice(100.0, 1L); // establishes the stop
        f.notifier().messages.clear();

        f.feed().fail("stream closed (1006 abnormal)");

        assertTrue(f.notifier().anyContains("price feed dropped"));
        assertTrue(f.notifier().anyContains("NOT being enforced"));
    }

    /** A flapping connection must not spam the chat. */
    @Test
    void shouldReportAnOutageOnlyOnce() {

        Fixture f = fixture();
        open(f);
        f.notifier().messages.clear();

        f.feed().fail("one");
        f.feed().fail("two");
        f.feed().fail("three");

        assertEquals(1, f.notifier().messages.stream().filter(m -> m.contains("price feed dropped")).count());
    }

    @Test
    void shouldAnnounceRecoveryWhenPricesResume() {

        Fixture f = fixture();
        open(f);
        f.feed().fail("dropped");
        f.notifier().messages.clear();

        f.feed().listener.onPrice(101.0, 2L);

        assertTrue(f.notifier().anyContains("price feed recovered"));
    }

    @Test
    void shouldReportAFreshOutageAfterRecovery() {

        Fixture f = fixture();
        open(f);
        f.feed().fail("first");
        f.feed().listener.onPrice(101.0, 2L);
        f.notifier().messages.clear();

        f.feed().fail("second");

        assertTrue(f.notifier().anyContains("price feed dropped"));
    }

    /**
     * A disconnect we asked for is not a failure. Reporting it would cry
     * wolf on every closed trade.
     */
    @Test
    void shouldNotReportFailuresForAClosedTrade() {

        Fixture f = fixture();
        open(f);
        f.monitor().onExit("order-1");
        f.notifier().messages.clear();

        f.feed().fail("closed by us");

        assertFalse(f.notifier().anyContains("price feed dropped"));
    }

    @Test
    void shouldNotReportFailuresForAReleasedTrade() {

        Fixture f = fixture();
        open(f);
        f.monitor().onMonitorModeRequested("order-1", MonitorMode.RELEASED);
        f.notifier().messages.clear();

        f.feed().fail("closed by us");

        assertFalse(f.notifier().anyContains("price feed dropped"));
    }
}
