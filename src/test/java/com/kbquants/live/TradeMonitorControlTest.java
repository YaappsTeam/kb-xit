package com.kbquants.live;

import com.kbquants.domain.MonitorMode;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The off switches: pausing adoption, and handing a trade back without
 * closing it.
 * <p>
 * The distinction under test is that /release stops the engine acting
 * while the position stays open, whereas /exit sells it. Invisible in
 * paper mode, but once real sell orders are placed it is the difference
 * between disengaging and liquidating.
 */
class TradeMonitorControlTest {

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

        @Override
        public void start(PriceListener listener) {
            this.listener = listener;
        }
    }

    private static final class RecordingNotifier implements Notifier {
        final List<String> messages = new ArrayList<>();

        @Override
        public void send(String message) {
            messages.add(message);
        }

        String last() {
            return messages.get(messages.size() - 1);
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

    @Test
    void pauseShouldStopNewTradesBeingAdopted() {

        Fixture f = fixture();
        f.monitor().onAdoptionPaused(true);
        f.notifier().messages.clear();

        open(f);

        assertTrue(f.notifier().anyContains("adoption is paused"));
        assertFalse(f.notifier().anyContains("now tracking"));
    }

    @Test
    void resumeShouldAllowAdoptionAgain() {

        Fixture f = fixture();
        f.monitor().onAdoptionPaused(true);
        f.monitor().onAdoptionPaused(false);
        f.notifier().messages.clear();

        open(f);

        assertTrue(f.notifier().anyContains("now tracking"));
    }

    /**
     * Pausing must not be mistaken for "stop everything" -- open trades are
     * still managed, and the message has to say so.
     */
    @Test
    void pauseShouldSayThatOpenTradesRemainManaged() {

        Fixture f = fixture();
        open(f);
        f.notifier().messages.clear();

        f.monitor().onAdoptionPaused(true);

        assertTrue(f.notifier().last().contains("still being managed"), f.notifier().last());
    }

    /** Released trades stay open -- that is the entire point. */
    @Test
    void releaseShouldStopMonitoringWithoutClosingThePosition() {

        Fixture f = fixture();
        open(f);
        f.monitor().onMonitorModeRequested("order-1", MonitorMode.RELEASED);
        f.notifier().messages.clear();

        // Far below any stop; a managed trade would exit here.
        f.feed().listener.onPrice(10.0, 1L);

        assertTrue(f.notifier().messages.isEmpty(), () -> "expected silence, got " + f.notifier().messages);

        f.monitor().onStatusRequested();
        assertTrue(f.notifier().last().contains("order-1"), "position should still be open");
    }

    @Test
    void releaseShouldReportTheProtectionBeingGivenUp() {

        Fixture f = fixture();
        open(f);
        f.feed().listener.onPrice(100.0, 1L); // establishes the hard stop
        f.notifier().messages.clear();

        f.monitor().onMonitorModeRequested("order-1", MonitorMode.RELEASED);

        assertTrue(f.notifier().last().contains("released"));
        assertTrue(f.notifier().last().contains("protection is gone"), f.notifier().last());
    }

    /**
     * Observe reports the breach and stops there, so the user keeps the
     * information and the decision.
     */
    @Test
    void observeShouldReportStopBreachWithoutExiting() {

        Fixture f = fixture();
        open(f);
        f.monitor().onMonitorModeRequested("order-1", MonitorMode.OBSERVED);
        f.notifier().messages.clear();

        f.feed().listener.onPrice(10.0, 1L);

        assertTrue(f.notifier().anyContains("observing only, no exit placed"));
        assertFalse(f.notifier().anyContains("trade closed"));

        f.monitor().onStatusRequested();
        assertTrue(f.notifier().last().contains("order-1"), "position should still be open");
    }

    @Test
    void observedStopBreachShouldBeReportedOnlyOnce() {

        Fixture f = fixture();
        open(f);
        f.monitor().onMonitorModeRequested("order-1", MonitorMode.OBSERVED);
        f.notifier().messages.clear();

        f.feed().listener.onPrice(10.0, 1L);
        f.feed().listener.onPrice(9.0, 2L);
        f.feed().listener.onPrice(8.0, 3L);

        assertEquals(1, f.notifier().messages.stream().filter(m -> m.contains("observing only")).count());
    }

    @Test
    void manageShouldRestoreAutomaticExits() {

        Fixture f = fixture();
        open(f);
        f.monitor().onMonitorModeRequested("order-1", MonitorMode.OBSERVED);
        f.monitor().onMonitorModeRequested("order-1", MonitorMode.MANAGED);
        f.notifier().messages.clear();

        f.feed().listener.onPrice(10.0, 1L);

        assertTrue(f.notifier().anyContains("trade closed"));
    }

    @Test
    void releaseAllShouldApplyToEveryOpenTrade() {

        Fixture f = fixture();
        f.fills().listener.onFill(new TradeFillEvent("order-1", "A", 100.0, 10));
        f.fills().listener.onFill(new TradeFillEvent("order-2", "B", 200.0, 10));
        f.notifier().messages.clear();

        f.monitor().onMonitorModeRequested("all", MonitorMode.RELEASED);

        assertEquals(2, f.notifier().messages.stream().filter(m -> m.contains("released")).count());
    }

    @Test
    void shouldReportWhenTheTargetTradeIsUnknown() {

        Fixture f = fixture();

        f.monitor().onMonitorModeRequested("no-such-order", MonitorMode.RELEASED);

        assertTrue(f.notifier().last().contains("No active trade found"));
    }

    @Test
    void statusShouldShowModeAndPausedState() {

        Fixture f = fixture();
        open(f);
        f.monitor().onMonitorModeRequested("order-1", MonitorMode.OBSERVED);
        f.monitor().onAdoptionPaused(true);
        f.notifier().messages.clear();

        f.monitor().onStatusRequested();

        assertTrue(f.notifier().last().contains("OBSERVED"), f.notifier().last());
        assertTrue(f.notifier().last().contains("adoption paused"), f.notifier().last());
    }

    /**
     * The end-of-day close exists so intraday positions are not left for
     * the broker to square off at whatever price it gets.
     */
    @Test
    void endOfDayShouldCloseManagedTrades() {

        Fixture f = fixture();
        open(f);
        f.feed().listener.onPrice(105.0, 1L);
        f.notifier().messages.clear();

        f.monitor().onEndOfDay();

        assertTrue(f.notifier().anyContains("end-of-day exit"));
        f.monitor().onStatusRequested();
        assertTrue(f.notifier().last().contains("No active trades"));
    }

    /**
     * OBSERVED means the user took the trigger back. Silently selling a
     * position someone explicitly took manual control of is the one thing
     * that mode promises not to do, so it is told instead.
     */
    @Test
    void endOfDayShouldWarnRatherThanSellAnObservedTrade() {

        Fixture f = fixture();
        open(f);
        f.monitor().onMonitorModeRequested("order-1", MonitorMode.OBSERVED);
        f.notifier().messages.clear();

        f.monitor().onEndOfDay();

        assertTrue(f.notifier().anyContains("only being observed"));
        assertFalse(f.notifier().anyContains("end-of-day exit"));

        f.monitor().onStatusRequested();
        assertTrue(f.notifier().last().contains("order-1"), "position should still be open");
    }

    @Test
    void endOfDayShouldLeaveReleasedTradesAlone() {

        Fixture f = fixture();
        open(f);
        f.monitor().onMonitorModeRequested("order-1", MonitorMode.RELEASED);
        f.notifier().messages.clear();

        f.monitor().onEndOfDay();

        assertTrue(f.notifier().messages.isEmpty(), () -> f.notifier().messages.toString());
    }

    @Test
    void endOfDayShouldDoNothingWithNoOpenTrades() {

        Fixture f = fixture();

        f.monitor().onEndOfDay();

        assertTrue(f.notifier().messages.isEmpty());
    }
}
