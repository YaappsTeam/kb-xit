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
import org.slf4j.MDC;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * accountId/orderId in MDC around a detected fill and its price updates --
 * the two entry points that arrive from a feed rather than a Telegram
 * command, so TradeMonitor has to set its own context for them. The
 * Telegram-command half of this (one central wrap in
 * TelegramCommandHandler.withAccountLogContext, covering every
 * TelegramCommandListener method) is tested in TelegramCommandHandlerTest.
 */
class TradeMonitorLoggingContextTest {

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

    /** Captures whatever is in MDC at the moment a notification is sent. */
    private static final class MdcCapturingNotifier implements Notifier {
        String accountIdSeenAtLastSend;
        String orderIdSeenAtLastSend;

        @Override
        public void send(String message) {
            accountIdSeenAtLastSend = MDC.get("accountId");
            orderIdSeenAtLastSend = MDC.get("orderId");
        }

        @Override
        public void sendChoices(String prompt, LinkedHashMap<String, String> choices) {
            accountIdSeenAtLastSend = MDC.get("accountId");
            orderIdSeenAtLastSend = MDC.get("orderId");
        }
    }

    @Test
    void aDetectedFillCarriesAccountAndOrderIdInMdcWhileHandled() {

        MdcCapturingNotifier notifier = new MdcCapturingNotifier();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();

        new TradeMonitor(fills, fill -> feed, notifier, new LiteralTrackRequestResolver(),
                new ActiveLadder(MilestoneLadder.equityLadder()), new EstimatedChargesService(),
                new RiskSettings(0), new NoOpTradeStore(), new PaperExitOrderPlacer(),
                new TradingToken(ZoneId.of("Asia/Kolkata")), true, new NoOpPositionQuery(),
                Optional.empty(), "acct-7");

        fills.listener.onFill(new TradeFillEvent("broker-1", "NSE_EQ|X", 100.0, 10, 0.05, "ACC"));

        assertEquals("acct-7", notifier.accountIdSeenAtLastSend);
        assertEquals("broker-1", notifier.orderIdSeenAtLastSend);
    }

    @Test
    void mdcIsClearedAfterTheFillIsHandled() {

        MdcCapturingNotifier notifier = new MdcCapturingNotifier();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();

        new TradeMonitor(fills, fill -> new FakeFeed(), notifier, new LiteralTrackRequestResolver(),
                new ActiveLadder(MilestoneLadder.equityLadder()), new EstimatedChargesService(),
                new RiskSettings(0), new NoOpTradeStore(), new PaperExitOrderPlacer(),
                new TradingToken(ZoneId.of("Asia/Kolkata")), true, new NoOpPositionQuery(),
                Optional.empty(), "acct-7");

        fills.listener.onFill(new TradeFillEvent("broker-1", "NSE_EQ|X", 100.0, 10, 0.05, "ACC"));

        assertNull(MDC.get("accountId"), "MDC must not leak into whatever runs next on this thread");
        assertNull(MDC.get("orderId"));
    }

    @Test
    void priceUpdatesOnAnAdoptedTradeAlsoCarryTheAccountAndOrderId() {

        MdcCapturingNotifier notifier = new MdcCapturingNotifier();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();

        TradeMonitor monitor = new TradeMonitor(fills, fill -> feed, notifier, new LiteralTrackRequestResolver(),
                new ActiveLadder(MilestoneLadder.equityLadder()), new EstimatedChargesService(),
                new RiskSettings(0), new NoOpTradeStore(), new PaperExitOrderPlacer(),
                new TradingToken(ZoneId.of("Asia/Kolkata")), false, new NoOpPositionQuery(),
                Optional.empty(), "acct-7");

        fills.listener.onFill(new TradeFillEvent("broker-1", "NSE_EQ|X", 100.0, 10, 0.05, "ACC"));
        notifier.accountIdSeenAtLastSend = null;
        notifier.orderIdSeenAtLastSend = null;

        feed.listener.onPrice(150.0, System.currentTimeMillis());

        assertEquals("acct-7", notifier.accountIdSeenAtLastSend);
        assertEquals("broker-1", notifier.orderIdSeenAtLastSend);
        assertNull(MDC.get("accountId"), "cleared again after the price update finishes");
    }
}
