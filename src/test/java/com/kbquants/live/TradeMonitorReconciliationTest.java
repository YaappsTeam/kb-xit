package com.kbquants.live;

import com.kbquants.domain.ActiveLadder;
import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.MonitorMode;
import com.kbquants.domain.RiskSettings;
import com.kbquants.domain.TradingToken;
import com.kbquants.notification.Notifier;
import com.kbquants.session.BrokerPosition;
import com.kbquants.session.EstimatedChargesService;
import com.kbquants.session.ExitOrderPlacer;
import com.kbquants.session.ExitReason;
import com.kbquants.session.LiteralTrackRequestResolver;
import com.kbquants.session.MarketDataFeed;
import com.kbquants.session.NoOpTradeStore;
import com.kbquants.session.OrderFillFeed;
import com.kbquants.session.PositionQuery;
import com.kbquants.session.PositionQueryException;
import com.kbquants.session.PriceListener;
import com.kbquants.session.TradeFillEvent;
import com.kbquants.session.TradeFillListener;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the engine does once the broker disagrees with it.
 * <p>
 * The rule throughout: a discrepancy stops the engine <em>acting</em>, and
 * nothing more. Acting is what makes a stale trade dangerous -- an exit
 * sized from a position that has shrunk sells quantity that is not there,
 * and selling past flat opens a short. Dropping the trade instead would be
 * unrecoverable if the broker were the one that was wrong.
 */
class TradeMonitorReconciliationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

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
        final List<LinkedHashMap<String, String>> choices = new ArrayList<>();

        @Override
        public void send(String message) {
            messages.add(message);
        }

        @Override
        public void sendChoices(String prompt, LinkedHashMap<String, String> choices) {
            messages.add(prompt);
            this.choices.add(choices);
        }

        String last() {
            return messages.get(messages.size() - 1);
        }

        boolean anyContains(String fragment) {
            return messages.stream().anyMatch(m -> m.contains(fragment));
        }
    }

    /** Answers whatever the test sets, or refuses to answer at all. */
    private static final class FakePositionQuery implements PositionQuery {
        List<BrokerPosition> positions = List.of();
        String failure;
        boolean available = true;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public List<BrokerPosition> openPositions() throws PositionQueryException {
            if (failure != null) {
                throw new PositionQueryException(failure);
            }
            return positions;
        }
    }

    private static final class RecordingPlacer implements ExitOrderPlacer {
        final List<String> calls = new ArrayList<>();

        @Override
        public Result placeExit(TradeFillEvent fill, double price, ExitReason reason) {
            calls.add(reason + " x" + fill.getFilledQuantity());
            return Result.placed("BROKER-1");
        }
    }

    private record Fixture(TradeMonitor monitor, FakeOrderFillFeed fills, FakeFeed feed,
                           RecordingNotifier notifier, FakePositionQuery query, RecordingPlacer placer) {}

    private static Fixture fixture() {
        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();
        FakePositionQuery query = new FakePositionQuery();
        RecordingPlacer placer = new RecordingPlacer();
        TradeMonitor monitor = new TradeMonitor(fills, fill -> feed, notifier,
                new LiteralTrackRequestResolver(), new ActiveLadder(MilestoneLadder.equityLadder()),
                new EstimatedChargesService(), new RiskSettings(0), new NoOpTradeStore(),
                placer, new TradingToken(IST), false, query);
        return new Fixture(monitor, fills, feed, notifier, query, placer);
    }

    private static void open(Fixture f) {
        f.fills().listener.onFill(new TradeFillEvent("order-1", "NSE_EQ|ACC", 100.0, 10, 0.05, "ACC"));
    }

    private static BrokerPosition position(String instrument, int quantity) {
        return new BrokerPosition(instrument, quantity, 100.0, instrument, "I");
    }

    // --- the dangerous case ---

    /**
     * The position was closed by hand. Left managed, its stop would place a
     * sell for something that is not there -- which is a short.
     */
    @Test
    void aTradeTheBrokerDoesNotHoldShouldStopBeingActedOn() {

        Fixture f = fixture();
        open(f);
        f.query().positions = List.of();
        f.notifier().messages.clear();

        f.monitor().onReconcileRequested();

        assertTrue(f.notifier().anyContains("not open at your broker"));
        f.notifier().messages.clear();
        f.monitor().onStatusRequested();
        assertTrue(f.notifier().last().contains("OBSERVED"), f.notifier().last());
    }

    @Test
    void aStoodDownTradeShouldNotPlaceAnOrderWhenItsStopIsBreached() {

        Fixture f = fixture();
        open(f);
        f.query().positions = List.of();
        f.monitor().onReconcileRequested();

        f.feed().listener.onPrice(10.0, 1L);

        assertTrue(f.placer().calls.isEmpty(), "must not sell a position the broker does not hold");
    }

    /** Still watched, so the user keeps the information they need to act. */
    @Test
    void aStoodDownTradeShouldStillBeTracked() {

        Fixture f = fixture();
        open(f);
        f.query().positions = List.of();
        f.monitor().onReconcileRequested();
        f.notifier().messages.clear();

        f.monitor().onStatusRequested();

        assertTrue(f.notifier().last().contains("order-1"), "standing down is not dropping");
    }

    @Test
    void aPartiallySoldTradeShouldAlsoStopBeingActedOn() {

        Fixture f = fixture();
        open(f);
        f.query().positions = List.of(position("NSE_EQ|ACC", 4));
        f.notifier().messages.clear();

        f.monitor().onReconcileRequested();

        assertTrue(f.notifier().anyContains("broker has 4, this system has 10"));
        f.feed().listener.onPrice(10.0, 1L);
        assertTrue(f.placer().calls.isEmpty(), "selling 10 against 4 held would go short");
    }

    // --- agreement ---

    @Test
    void aMatchingPositionShouldBeLeftAlone() {

        Fixture f = fixture();
        open(f);
        f.query().positions = List.of(position("NSE_EQ|ACC", 10));
        f.notifier().messages.clear();

        f.monitor().onReconcileRequested();

        assertTrue(f.notifier().anyContains("1 confirmed still open"));
        f.notifier().messages.clear();
        f.monitor().onStatusRequested();
        assertFalse(f.notifier().last().contains("OBSERVED"));
    }

    @Test
    void aConfirmedTradeShouldStillExitOnItsStop() {

        Fixture f = fixture();
        open(f);
        f.query().positions = List.of(position("NSE_EQ|ACC", 10));
        f.monitor().onReconcileRequested();

        f.feed().listener.onPrice(10.0, 1L);

        assertFalse(f.placer().calls.isEmpty(), "agreement must not disarm the engine");
    }

    // --- not being able to ask ---

    /**
     * The most important negative: a failed check must change nothing. An
     * error read as "the broker holds nothing" would stand down every open
     * trade at once, removing protection from all of them.
     */
    @Test
    void aFailedCheckShouldChangeNothing() {

        Fixture f = fixture();
        open(f);
        f.query().failure = "connection reset";
        f.notifier().messages.clear();

        f.monitor().onReconcileRequested();

        assertTrue(f.notifier().anyContains("Could not check your broker"));
        assertTrue(f.notifier().anyContains("still managed as they were"));
        f.feed().listener.onPrice(10.0, 1L);
        assertFalse(f.placer().calls.isEmpty(), "the engine must still be armed");
    }

    @Test
    void shouldSaySoWhenThereIsNoTokenToCheckWith() {

        Fixture f = fixture();
        open(f);
        f.query().available = false;
        f.notifier().messages.clear();

        f.monitor().onReconcileRequested();

        assertTrue(f.notifier().last().contains("Cannot check your broker"));
    }

    // --- positions nobody is watching ---

    /**
     * The fill feed only sees fills while it is connected, so a position
     * bought before startup is invisible to it. Reconciliation is what
     * finds those -- and, like any detected position, it is offered.
     */
    @Test
    void anUnmanagedBrokerPositionShouldBeOfferedForAdoption() {

        Fixture f = fixture();
        f.query().positions = List.of(position("NSE_FO|45148", 65));
        f.notifier().messages.clear();

        f.monitor().onReconcileRequested();

        assertTrue(f.notifier().anyContains("open at your broker, not managed here"));
    }

    @Test
    void aReconciledPositionShouldNotBeAdoptedWithoutBeingAsked() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        FakePositionQuery query = new FakePositionQuery();
        query.positions = List.of(position("NSE_FO|45148", 65));
        TradeMonitor monitor = new TradeMonitor(fills, fill -> new FakeFeed(), notifier,
                new LiteralTrackRequestResolver(), new ActiveLadder(MilestoneLadder.equityLadder()),
                new EstimatedChargesService(), new RiskSettings(0), new NoOpTradeStore(),
                new RecordingPlacer(), new TradingToken(IST), true, query);

        monitor.onReconcileRequested();

        notifier.messages.clear();
        monitor.onStatusRequested();
        assertEquals("No active trades", notifier.last());
        assertTrue(notifier.choices.stream()
                        .anyMatch(c -> c.values().stream().anyMatch(v -> v.startsWith("adopt:"))),
                "it should be offered, just not taken");
    }

    @Test
    void anAlreadyObservedTradeShouldNotBeReportedAsChanged() {

        Fixture f = fixture();
        open(f);
        f.monitor().onMonitorModeRequested("order-1", MonitorMode.OBSERVED);
        f.query().positions = List.of();
        f.notifier().messages.clear();

        f.monitor().onReconcileRequested();

        assertTrue(f.notifier().anyContains("already OBSERVED"));
    }

    @Test
    void nothingOpenAnywhereShouldReconcileQuietly() {

        Fixture f = fixture();
        f.notifier().messages.clear();

        f.monitor().onReconcileRequested();

        assertTrue(f.notifier().last().contains("0 confirmed still open"));
    }
}
