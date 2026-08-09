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
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-trade buttons. Orders are identified by generated UUIDs, so anything
 * that makes the user retype one mid-trade is friction worth removing.
 */
class TradeMonitorButtonsTest {

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

        LinkedHashMap<String, String> lastChoices() {
            return choices.get(choices.size() - 1);
        }
    }

    private record Fixture(TradeMonitor monitor, FakeOrderFillFeed fills, RecordingNotifier notifier) {}

    private static Fixture fixture() {
        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        TradeMonitor monitor = new TradeMonitor(fills, fill -> new FakeFeed(), notifier, RESOLVER);
        return new Fixture(monitor, fills, notifier);
    }

    private static void open(Fixture f, String orderId, String symbol) {
        f.fills().listener.onFill(new TradeFillEvent(orderId, "NSE_FO|45148", 55.30, 65, 0.05, symbol));
    }

    @Test
    void exitShouldOfferOneButtonPerOpenTrade() {

        Fixture f = fixture();
        open(f, "order-1", "NIFTY 25000 CE");
        open(f, "order-2", "NIFTY 25000 PE");
        f.notifier().messages.clear();

        f.monitor().onExitChoicesRequested();

        LinkedHashMap<String, String> choices = f.notifier().lastChoices();
        assertTrue(choices.containsValue("exit:order-1"));
        assertTrue(choices.containsValue("exit:order-2"));
        assertTrue(choices.containsValue("exit:all"));
    }

    /** Labels must be readable without checking /status first. */
    @Test
    void buttonLabelsShouldShowSymbolAndMode() {

        Fixture f = fixture();
        open(f, "order-1", "NIFTY 25000 CE");
        f.notifier().messages.clear();

        f.monitor().onExitChoicesRequested();

        String label = f.notifier().lastChoices().keySet().iterator().next();
        assertTrue(label.contains("NIFTY 25000 CE"), label);
        assertTrue(label.contains("MANAGED"), label);
    }

    /** "All" is noise when there is only one trade to act on. */
    @Test
    void shouldNotOfferAllForASingleTrade() {

        Fixture f = fixture();
        open(f, "order-1", "NIFTY 25000 CE");
        f.notifier().messages.clear();

        f.monitor().onExitChoicesRequested();

        assertFalse(f.notifier().lastChoices().containsValue("exit:all"));
        assertEquals(1, f.notifier().lastChoices().size());
    }

    @Test
    void modeCommandsShouldCarryTheirOwnCallbackPrefix() {

        Fixture f = fixture();
        open(f, "order-1", "NIFTY 25000 CE");

        f.monitor().onMonitorModeChoicesRequested(MonitorMode.RELEASED);
        assertTrue(f.notifier().lastChoices().containsValue("release:order-1"));

        f.monitor().onMonitorModeChoicesRequested(MonitorMode.OBSERVED);
        assertTrue(f.notifier().lastChoices().containsValue("observe:order-1"));

        f.monitor().onMonitorModeChoicesRequested(MonitorMode.MANAGED);
        assertTrue(f.notifier().lastChoices().containsValue("manage:order-1"));
    }

    /** Releasing gives up stop protection, so the prompt has to say so. */
    @Test
    void releasePromptShouldSayThePositionStaysOpen() {

        Fixture f = fixture();
        open(f, "order-1", "NIFTY 25000 CE");
        f.notifier().messages.clear();

        f.monitor().onMonitorModeChoicesRequested(MonitorMode.RELEASED);

        assertTrue(f.notifier().messages.get(0).contains("position stays open"),
                f.notifier().messages.get(0));
    }

    @Test
    void shouldSaySoWhenThereIsNothingToActOn() {

        Fixture f = fixture();

        f.monitor().onExitChoicesRequested();

        assertEquals(List.of("No open trades"), f.notifier().messages);
        assertTrue(f.notifier().choices.isEmpty(), "no keyboard when there is nothing to choose");
    }

    /** Closed trades must not appear as options. */
    @Test
    void shouldOfferOnlyOpenTrades() {

        Fixture f = fixture();
        open(f, "order-1", "NIFTY 25000 CE");
        open(f, "order-2", "NIFTY 25000 PE");
        f.monitor().onExit("order-1");
        f.notifier().messages.clear();

        f.monitor().onExitChoicesRequested();

        assertFalse(f.notifier().lastChoices().containsValue("exit:order-1"));
        assertTrue(f.notifier().lastChoices().containsValue("exit:order-2"));
    }

    /**
     * Telegram caps callback_data at 64 bytes. A truncated token would act
     * on the wrong trade, so such trades are named in text instead.
     */
    @Test
    void shouldFallBackToTextForOrderIdsTooLongForAButton() {

        Fixture f = fixture();
        String hugeId = "broker-" + "x".repeat(80);
        open(f, hugeId, "NIFTY 25000 CE");
        f.notifier().messages.clear();

        f.monitor().onExitChoicesRequested();

        assertTrue(f.notifier().choices.isEmpty(), "should not build an oversized button");
        assertTrue(f.notifier().messages.get(0).contains(hugeId), f.notifier().messages.get(0));
    }
}
