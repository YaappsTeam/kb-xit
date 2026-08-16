package com.kbquants.live;

import com.kbquants.domain.ActiveLadder;
import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.MonitorMode;
import com.kbquants.domain.RiskSettings;
import com.kbquants.domain.TradingToken;
import com.kbquants.notification.Notifier;
import com.kbquants.session.EstimatedChargesService;
import com.kbquants.session.LiteralTrackRequestResolver;
import com.kbquants.session.MarketDataFeed;
import com.kbquants.session.NoOpPositionQuery;
import com.kbquants.session.NoOpTradeStore;
import com.kbquants.session.OrderFillFeed;
import com.kbquants.session.PaperExitOrderPlacer;
import com.kbquants.session.PriceListener;
import com.kbquants.session.TradeFillEvent;
import com.kbquants.session.TradeFillListener;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** /health: feed state, last-tick age, and daily token validity per PRODUCT_REQUIREMENTS.md step 4.3. */
class TradeMonitorHealthTest {

    private static final class FakeOrderFillFeed implements OrderFillFeed {
        TradeFillListener listener;

        @Override
        public void start(TradeFillListener listener) {
            this.listener = listener;
        }

        @Override
        public void stop() {
        }

        @Override
        public void onCredentialAvailable() {
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

        @Override
        public void sendChoices(String prompt, LinkedHashMap<String, String> choices) {
            messages.add(prompt);
        }

        String last() {
            return messages.get(messages.size() - 1);
        }
    }

    private static TradeMonitor monitor(FakeOrderFillFeed fills, FakeFeed feed, Notifier notifier,
                                         TradingToken tradingToken) {
        return new TradeMonitor(fills, fill -> feed, notifier, new LiteralTrackRequestResolver(),
                new ActiveLadder(MilestoneLadder.equityLadder()), new EstimatedChargesService(),
                new RiskSettings(0), new NoOpTradeStore(), new PaperExitOrderPlacer(),
                tradingToken, false, new NoOpPositionQuery(), Optional.empty(), "acct-1");
    }

    @Test
    void withNoActiveTradesAndNoTokenShouldReportBoth() {

        RecordingNotifier notifier = new RecordingNotifier();
        TradingToken token = new TradingToken(ZoneId.of("Asia/Kolkata"));
        TradeMonitor monitor = monitor(new FakeOrderFillFeed(), new FakeFeed(), notifier, token);

        monitor.onHealthRequested();

        assertTrue(notifier.last().contains("No active trades"));
        assertTrue(notifier.last().contains("token: not usable"));
    }

    @Test
    void anAttachedFeedAndUsableTokenShouldBothReportHealthy() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();
        TradingToken token = new TradingToken(ZoneId.of("Asia/Kolkata"));
        TradeMonitor monitor = monitor(fills, feed, notifier, token);
        token.set("a-token");

        fills.listener.onFill(new TradeFillEvent("broker-1", "NSE_EQ|X", 100.0, 10, 0.05, "ACC"));
        notifier.messages.clear();

        monitor.onHealthRequested();

        assertTrue(notifier.last().contains("feed=attached"));
        assertTrue(notifier.last().contains("broker-1"));
        assertTrue(notifier.last().contains("token: usable"));
    }

    @Test
    void aReleasedTradesFeedShouldReportStopped() {

        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();
        TradeMonitor monitor = monitor(fills, feed, notifier, new TradingToken(ZoneId.of("Asia/Kolkata")));

        fills.listener.onFill(new TradeFillEvent("broker-1", "NSE_EQ|X", 100.0, 10, 0.05, "ACC"));
        monitor.onMonitorModeRequested("broker-1", MonitorMode.RELEASED);
        notifier.messages.clear();

        monitor.onHealthRequested();

        assertTrue(notifier.last().contains("feed=stopped"));
    }
}
