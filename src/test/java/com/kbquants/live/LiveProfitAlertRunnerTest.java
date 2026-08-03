package com.kbquants.live;

import com.kbquants.notification.Notifier;
import com.kbquants.session.MarketDataFeed;
import com.kbquants.session.PriceListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for LiveProfitAlertRunner using a fake MarketDataFeed factory,
 * so no real network connection is ever attempted.
 */
class LiveProfitAlertRunnerTest {

    /** Captures the PriceListener it was started with instead of connecting anywhere. */
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
    void shouldStartWatchingInstrumentOnFillAndNotifyAtFirstMilestone() {

        RecordingNotifier notifier = new RecordingNotifier();
        Map<String, FakeFeed> feedsByInstrument = new HashMap<>();

        LiveProfitAlertRunner runner = new LiveProfitAlertRunner(
                dummyCredentials(), notifier,
                instrumentKey -> feedsByInstrument.computeIfAbsent(instrumentKey, k -> new FakeFeed()));

        TradeFillEvent fill = new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10);
        runner.onFill(fill);

        FakeFeed feed = feedsByInstrument.get("NSE_EQ|INE848E01016");
        assertTrue(feed != null && feed.capturedListener != null);

        feed.capturedListener.onPrice(100.2, 1L); // +0.2%, below first threshold
        assertTrue(notifier.messages.isEmpty());

        feed.capturedListener.onPrice(100.5, 2L); // +0.5%
        assertEquals(1, notifier.messages.size());
        assertEquals(
                "NSE_EQ|INE848E01016 up 0.5% from entry (entry=100.00, current=100.50)",
                notifier.messages.get(0));
    }

    @Test
    void shouldIgnoreDuplicateFillEventsForSameOrderId() {

        Map<String, Integer> feedCreationCount = new HashMap<>();

        LiveProfitAlertRunner runner = new LiveProfitAlertRunner(
                dummyCredentials(), message -> { },
                instrumentKey -> {
                    feedCreationCount.merge(instrumentKey, 1, Integer::sum);
                    return new FakeFeed();
                });

        TradeFillEvent fill = new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10);

        runner.onFill(fill);
        runner.onFill(fill); // duplicate, e.g. re-delivered after a reconnect

        assertEquals(1, feedCreationCount.get("NSE_EQ|INE848E01016"));
    }

    @Test
    void shouldRatchetThroughMultipleMilestonesAcrossPriceUpdates() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeFeed feed = new FakeFeed();

        LiveProfitAlertRunner runner = new LiveProfitAlertRunner(
                dummyCredentials(), notifier, instrumentKey -> feed);

        TradeFillEvent fill = new TradeFillEvent("order-1", "NSE_EQ|INE848E01016", 100.0, 10);
        runner.onFill(fill);

        feed.capturedListener.onPrice(100.5, 1L); // +0.5%
        feed.capturedListener.onPrice(101.0, 2L); // +1.0%
        feed.capturedListener.onPrice(101.0, 3L); // repeat, no new milestone

        assertEquals(2, notifier.messages.size());
        assertTrue(notifier.messages.get(0).contains("0.5%"));
        assertTrue(notifier.messages.get(1).contains("1.0%"));
    }

    private static UpstoxCredentials dummyCredentials() {
        return new UpstoxCredentials("key", "secret", "https://example.com", "token", false);
    }
}
