package com.kbquants.live;

import com.kbquants.domain.ActiveLadder;
import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.RiskSettings;
import com.kbquants.notification.Notifier;
import com.kbquants.session.EstimatedChargesService;
import com.kbquants.session.ExitOrderPlacer;
import com.kbquants.session.ExitReason;
import com.kbquants.session.LiteralTrackRequestResolver;
import com.kbquants.session.MarketDataFeed;
import com.kbquants.session.NoOpTradeStore;
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
 * Exits go through an order placer, and a rejected order does not close
 * the trade.
 * <p>
 * The distinction only matters with real money: deciding to exit and
 * having exited are the same thing on paper and very different once a
 * broker can refuse. Treating a rejected sell as a close would stop the
 * engine managing a position that is still open.
 */
class TradeMonitorExitOrderTest {

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
        int stopCount;

        @Override
        public void start(PriceListener listener) {
            this.listener = listener;
        }

        @Override
        public void stop() {
            stopCount++;
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

    /** Records what it was asked to sell, and can be made to refuse. */
    private static final class RecordingPlacer implements ExitOrderPlacer {
        final List<String> calls = new ArrayList<>();
        boolean fail;

        @Override
        public Result placeExit(TradeFillEvent fill, double price, ExitReason reason) {
            calls.add(reason + "@" + price + " x" + fill.getFilledQuantity());
            return fail ? Result.failed("broker rejected: insufficient margin")
                        : Result.placed("BROKER-1");
        }
    }

    private record Fixture(TradeMonitor monitor, FakeOrderFillFeed fills, FakeFeed feed,
                           RecordingNotifier notifier, RecordingPlacer placer) {}

    private static Fixture fixture() {
        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();
        RecordingPlacer placer = new RecordingPlacer();
        TradeMonitor monitor = new TradeMonitor(fills, fill -> feed, notifier,
                new LiteralTrackRequestResolver(), new ActiveLadder(MilestoneLadder.equityLadder()),
                new EstimatedChargesService(), new RiskSettings(0), new NoOpTradeStore(), placer);
        return new Fixture(monitor, fills, feed, notifier, placer);
    }

    private static void open(Fixture f) {
        f.fills().listener.onFill(new TradeFillEvent("order-1", "NSE_EQ|X", 100.0, 10, 0.05, "ACC"));
    }

    @Test
    void manualExitShouldPlaceASellOrder() {

        Fixture f = fixture();
        open(f);

        f.monitor().onExit("order-1");

        assertEquals(1, f.placer().calls.size());
        assertTrue(f.placer().calls.get(0).contains("manual /exit"), f.placer().calls.toString());
        assertTrue(f.placer().calls.get(0).contains("x10"), "must sell the quantity held");
    }

    @Test
    void stopLossShouldPlaceASellOrder() {

        Fixture f = fixture();
        open(f);

        f.feed().listener.onPrice(10.0, 1L);

        assertTrue(f.placer().calls.get(0).contains("stop-loss"), f.placer().calls.toString());
    }

    @Test
    void endOfDayShouldPlaceASellOrder() {

        Fixture f = fixture();
        open(f);

        f.monitor().onEndOfDay();

        assertTrue(f.placer().calls.get(0).contains("end-of-day"), f.placer().calls.toString());
    }

    // --- the case that matters ---

    /**
     * A rejected sell leaves the position open at the broker, so the trade
     * must stay under management rather than being reported as closed.
     */
    @Test
    void aRejectedExitShouldLeaveTheTradeOpenAndManaged() {

        Fixture f = fixture();
        open(f);
        f.placer().fail = true;
        f.notifier().messages.clear();

        f.monitor().onExit("order-1");

        assertTrue(f.notifier().anyContains("exit order FAILED"));
        assertTrue(f.notifier().anyContains("still open and still being managed"));

        f.monitor().onStatusRequested();
        assertTrue(f.notifier().last().contains("order-1"), "trade should still be tracked");
    }

    @Test
    void aRejectedExitShouldNotStopThePriceFeed() {

        Fixture f = fixture();
        open(f);
        f.placer().fail = true;

        f.monitor().onExit("order-1");

        assertEquals(0, f.feed().stopCount, "still-open positions need prices");
    }

    /** A rejected stop-loss must be retried on the next tick, not abandoned. */
    @Test
    void aRejectedStopLossShouldBeRetriedOnTheNextTick() {

        Fixture f = fixture();
        open(f);
        f.placer().fail = true;

        f.feed().listener.onPrice(10.0, 1L);
        f.feed().listener.onPrice(10.0, 2L);

        assertTrue(f.placer().calls.size() >= 2,
                "expected another attempt, got " + f.placer().calls.size());
    }

    @Test
    void aSucceedingExitShouldCloseAndStopTheFeed() {

        Fixture f = fixture();
        open(f);

        f.monitor().onExit("order-1");

        assertEquals(1, f.feed().stopCount);
        f.notifier().messages.clear();
        f.monitor().onStatusRequested();
        assertEquals("No active trades", f.notifier().last());
    }

    @Test
    void observedTradesShouldNotPlaceOrdersAtEndOfDay() {

        Fixture f = fixture();
        open(f);
        f.monitor().onMonitorModeRequested("order-1", com.kbquants.domain.MonitorMode.OBSERVED);

        f.monitor().onEndOfDay();

        assertTrue(f.placer().calls.isEmpty(), "observing must never place an order");
    }

    @Test
    void releasedTradesShouldNotPlaceOrdersAtEndOfDay() {

        Fixture f = fixture();
        open(f);
        f.monitor().onMonitorModeRequested("order-1", com.kbquants.domain.MonitorMode.RELEASED);

        f.monitor().onEndOfDay();

        assertTrue(f.placer().calls.isEmpty());
    }

    @Test
    void observedTradesShouldNotPlaceOrdersWhenTheStopIsBreached() {

        Fixture f = fixture();
        open(f);
        f.monitor().onMonitorModeRequested("order-1", com.kbquants.domain.MonitorMode.OBSERVED);

        f.feed().listener.onPrice(10.0, 1L);

        assertTrue(f.placer().calls.isEmpty(), "observing reports the breach, it does not sell");
        assertFalse(f.notifier().anyContains("trade closed"));
    }
}
