package com.kbquants.live;

import com.kbquants.domain.ActiveLadder;
import com.kbquants.domain.MilestoneLadder;
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
import java.util.LinkedHashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Graceful shutdown: close every open trade's feed, per IMPLEMENTATION_PLAN.md step 4.3. */
class TradeMonitorShutdownTest {

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
        boolean stopped;

        @Override
        public void start(PriceListener listener) {
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    private static final class NoOpNotifier implements Notifier {
        @Override
        public void send(String message) {
        }

        @Override
        public void sendChoices(String prompt, LinkedHashMap<String, String> choices) {
        }
    }

    private static TradeMonitor monitor(FakeOrderFillFeed fills, FakeFeed feed) {
        return new TradeMonitor(fills, fill -> feed, new NoOpNotifier(), new LiteralTrackRequestResolver(),
                new ActiveLadder(MilestoneLadder.equityLadder()), new EstimatedChargesService(),
                new RiskSettings(0), new NoOpTradeStore(), new PaperExitOrderPlacer(),
                new TradingToken(ZoneId.of("Asia/Kolkata")), false, new NoOpPositionQuery(),
                Optional.empty(), "acct-1");
    }

    @Test
    void shutdownStopsTheFeedOfEveryOpenTrade() {

        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();
        TradeMonitor monitor = monitor(fills, feed);

        fills.listener.onFill(new TradeFillEvent("broker-1", "NSE_EQ|X", 100.0, 10, 0.05, "ACC"));

        monitor.shutdown();

        assertTrue(feed.stopped);
    }

    @Test
    void shutdownWithNoOpenTradesDoesNothingHarmful() {

        TradeMonitor monitor = monitor(new FakeOrderFillFeed(), new FakeFeed());

        assertDoesNotThrow(monitor::shutdown);
    }

    @Test
    void shutdownIsSafeToCallTwice() {

        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();
        TradeMonitor monitor = monitor(fills, feed);

        fills.listener.onFill(new TradeFillEvent("broker-1", "NSE_EQ|X", 100.0, 10, 0.05, "ACC"));

        monitor.shutdown();
        assertDoesNotThrow(monitor::shutdown);
    }
}
