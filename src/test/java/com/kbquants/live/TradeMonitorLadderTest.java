package com.kbquants.live;

import com.kbquants.domain.MilestoneLadder;
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
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for selecting the active milestone set at runtime.
 */
class TradeMonitorLadderTest {

    private static final TrackRequestResolver RESOLVER = new LiteralTrackRequestResolver();

    private static final class FakeOrderFillFeed implements OrderFillFeed {
        TradeFillListener capturedListener;

        @Override
        public void start(TradeFillListener listener) {
            this.capturedListener = listener;
        }

        @Override
        public void stop() {
        }
    }

    private static final class FakeFeed implements MarketDataFeed {
        PriceListener capturedListener;

        @Override
        public void start(PriceListener listener) {
            this.capturedListener = listener;
        }
    }

    private static final class RecordingNotifier implements Notifier {
        final List<String> messages = new ArrayList<>();
        final List<LinkedHashMap<String, String>> choicesSent = new ArrayList<>();

        @Override
        public void send(String message) {
            messages.add(message);
        }

        @Override
        public void sendChoices(String prompt, LinkedHashMap<String, String> choices) {
            messages.add(prompt);
            choicesSent.add(choices);
        }

        String last() {
            return messages.get(messages.size() - 1);
        }
    }

    @Test
    void shouldPushAvailableSetsAsChoices() {

        RecordingNotifier notifier = new RecordingNotifier();
        TradeMonitor monitor = new TradeMonitor(new FakeOrderFillFeed(), fill -> new FakeFeed(), notifier, RESOLVER);

        monitor.onLadderChoicesRequested();

        assertEquals(1, notifier.choicesSent.size());
        LinkedHashMap<String, String> choices = notifier.choicesSent.get(0);
        assertEquals(2, choices.size());
        assertTrue(choices.values().stream().anyMatch(token -> token.equals("ladder:OPTIONS")));
        assertTrue(choices.values().stream().anyMatch(token -> token.equals("ladder:EQUITY")));
    }

    @Test
    void shouldMarkTheActiveSetInTheChoices() {

        RecordingNotifier notifier = new RecordingNotifier();
        TradeMonitor monitor = new TradeMonitor(new FakeOrderFillFeed(), fill -> new FakeFeed(), notifier, RESOLVER);

        monitor.onLadderChoicesRequested();

        assertTrue(notifier.choicesSent.get(0).keySet().stream().anyMatch(label -> label.contains("active")));
    }

    @Test
    void shouldSwitchTheActiveSet() {

        RecordingNotifier notifier = new RecordingNotifier();
        TradeMonitor monitor = new TradeMonitor(new FakeOrderFillFeed(), fill -> new FakeFeed(), notifier, RESOLVER);

        monitor.onLadderSelected("OPTIONS");

        assertTrue(notifier.last().contains("OPTIONS"));
        assertTrue(notifier.last().contains("now"));
    }

    @Test
    void shouldRejectUnknownSet() {

        RecordingNotifier notifier = new RecordingNotifier();
        TradeMonitor monitor = new TradeMonitor(new FakeOrderFillFeed(), fill -> new FakeFeed(), notifier, RESOLVER);

        monitor.onLadderSelected("NOSUCHSET");

        assertTrue(notifier.last().contains("Unknown milestone set"));
    }

    @Test
    void shouldReportWhenTheChosenSetIsAlreadyActive() {

        RecordingNotifier notifier = new RecordingNotifier();
        TradeMonitor monitor = new TradeMonitor(new FakeOrderFillFeed(), fill -> new FakeFeed(), notifier, RESOLVER);

        monitor.onLadderSelected("EQUITY");

        assertTrue(notifier.last().contains("already active"));
    }

    /**
     * Selecting a set is a pre-trade decision: an open trade keeps the
     * ladder it was opened with, so "active" would otherwise stop meaning
     * what is actually managing the position.
     */
    @Test
    void shouldRefuseToSwitchWhileATradeIsOpen() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        TradeMonitor monitor = new TradeMonitor(fills, fill -> new FakeFeed(), notifier, RESOLVER);

        fills.capturedListener.onFill(new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10));
        monitor.onLadderSelected("OPTIONS");

        assertTrue(notifier.last().contains("Cannot change milestone set"));
        assertTrue(notifier.last().contains("1 trade(s) open"));
    }

    @Test
    void shouldAllowSwitchingAgainOnceTradesAreClosed() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        TradeMonitor monitor = new TradeMonitor(fills, fill -> new FakeFeed(), notifier, RESOLVER);

        fills.capturedListener.onFill(new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10));
        monitor.onExitAll();
        monitor.onLadderSelected("OPTIONS");

        assertTrue(notifier.last().contains("now"));
    }

    /**
     * The selected set has to actually reach the engine, not just the
     * confirmation message -- the option ladder's wider hard stop is the
     * observable difference.
     */
    @Test
    void newTradeShouldUseTheSelectedSetsHardStop() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();
        TradeMonitor monitor = new TradeMonitor(fills, fill -> feed, notifier, RESOLVER);

        monitor.onLadderSelected("OPTIONS");
        fills.capturedListener.onFill(new TradeFillEvent("order-1", "NSE_FO|45148", 100.0, 65));
        feed.capturedListener.onPrice(100.0, 1L);

        // 40% hard stop from the OPTIONS set, not the equity set's 20%.
        monitor.onStatusRequested();
        assertTrue(notifier.last().contains("sl=60.00"), notifier.last());
    }

    @Test
    void openTradeShouldKeepItsOwnLadderAfterASwitch() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();
        TradeMonitor monitor = new TradeMonitor(fills, fill -> feed, notifier,
                RESOLVER, MilestoneLadder.equityLadder());

        fills.capturedListener.onFill(new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10));
        feed.capturedListener.onPrice(100.0, 1L);
        monitor.onLadderSelected("OPTIONS"); // refused, trade is open

        monitor.onStatusRequested();
        assertTrue(notifier.last().contains("sl=80.00"), notifier.last());
    }
}
