package com.kbquants.live;

import com.kbquants.domain.ActiveLadder;
import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.MonitorMode;
import com.kbquants.domain.Phase;
import com.kbquants.domain.RiskSettings;
import com.kbquants.notification.Notifier;
import com.kbquants.session.EstimatedChargesService;
import com.kbquants.session.JsonTradeStore;
import com.kbquants.session.LiteralTrackRequestResolver;
import com.kbquants.session.MarketDataFeed;
import com.kbquants.session.OrderFillFeed;
import com.kbquants.session.PriceListener;
import com.kbquants.session.TradeFillEvent;
import com.kbquants.session.TradeFillListener;
import com.kbquants.session.TradeSnapshot;
import com.kbquants.session.TradeStore;
import com.kbquants.session.TrackRequestResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surviving a restart.
 * <p>
 * The position does not disappear when the process does. Without this,
 * a crash loses the entry, the cost-inclusive breakeven, the phase and the
 * ratcheted stop, while the trade sits open at the broker with nothing
 * watching it.
 */
class TradeMonitorPersistenceTest {

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

        boolean anyContains(String fragment) {
            return messages.stream().anyMatch(m -> m.contains(fragment));
        }
    }

    /** In-memory store so the round trip can be exercised without a temp file. */
    private static final class MemoryStore implements TradeStore {
        List<TradeSnapshot> saved = List.of();
        int saveCount;

        @Override
        public void save(List<TradeSnapshot> trades) {
            saved = trades;
            saveCount++;
        }

        @Override
        public List<TradeSnapshot> load() {
            return saved;
        }
    }

    private static TradeMonitor monitor(FakeOrderFillFeed fills, FakeFeed feed,
                                        Notifier notifier, TradeStore store) {
        return new TradeMonitor(fills, fill -> feed, notifier, RESOLVER,
                new ActiveLadder(MilestoneLadder.equityLadder()), new EstimatedChargesService(),
                new RiskSettings(0), store);
    }

    @Test
    void shouldPersistATrackedTrade() {

        MemoryStore store = new MemoryStore();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        monitor(fills, new FakeFeed(), new RecordingNotifier(), store);

        fills.listener.onFill(new TradeFillEvent("order-1", "NSE_EQ|X", 100.0, 10, 0.05, "ACC"));

        assertEquals(1, store.saved.size());
        assertEquals("order-1", store.saved.get(0).getOrderId());
        assertEquals("ACC", store.saved.get(0).getDisplaySymbol());
    }

    /**
     * The ratcheted stop is the field that matters most. It may have been
     * tightened well above the hard stop over the life of the trade, and
     * restoring anything else would silently give that protection back.
     */
    @Test
    void shouldRestoreTheRatchetedStopRatherThanRecomputingIt() {

        MemoryStore store = new MemoryStore();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();
        monitor(fills, feed, new RecordingNotifier(), store);

        fills.listener.onFill(new TradeFillEvent("order-1", "NSE_EQ|X", 100.0, 10, 0.05, "ACC"));
        feed.listener.onPrice(140.0, 1L); // drives phases and ratchets the stop up
        double ratcheted = store.saved.get(0).getCurrentStopLoss();
        assertTrue(ratcheted > 80.0, "expected a ratcheted stop, got " + ratcheted);

        // Restart.
        RecordingNotifier notifier = new RecordingNotifier();
        TradeMonitor resumed = monitor(new FakeOrderFillFeed(), new FakeFeed(), notifier, store);

        resumed.onStatusRequested();
        assertTrue(notifier.anyContains(String.format("sl=%.2f", ratcheted)),
                () -> "stop not restored: " + notifier.messages);
    }

    @Test
    void shouldRestorePhaseAndBreakeven() {

        MemoryStore store = new MemoryStore();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();
        monitor(fills, feed, new RecordingNotifier(), store);

        fills.listener.onFill(new TradeFillEvent("order-1", "NSE_EQ|X", 100.0, 10, 0.05, "ACC"));

        // Two ticks, because PhaseManager advances one phase per call: a
        // price gapping through both thresholds takes two ticks to catch
        // up. (That contradicts PRODUCT_REQUIREMENTS F4, which says all
        // intermediate transitions apply in one tick -- a separate defect.)
        feed.listener.onPrice(140.0, 1L);
        feed.listener.onPrice(140.0, 2L);

        TradeSnapshot snapshot = store.saved.get(0);
        assertEquals(Phase.PHASE_3.name(), snapshot.getPhase());

        RecordingNotifier notifier = new RecordingNotifier();
        monitor(new FakeOrderFillFeed(), new FakeFeed(), notifier, store).onStatusRequested();

        assertTrue(notifier.anyContains("PHASE_3"), () -> notifier.messages.toString());
        assertTrue(notifier.anyContains(String.format("breakeven=%.2f", snapshot.getBasePrice())),
                () -> notifier.messages.toString());
    }

    /**
     * Without the milestone index, every threshold already passed would
     * fire again on the first tick back.
     */
    @Test
    void shouldNotRefireMilestonesAlreadyReported() {

        MemoryStore store = new MemoryStore();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        FakeFeed feed = new FakeFeed();
        monitor(fills, feed, new RecordingNotifier(), store);

        fills.listener.onFill(new TradeFillEvent("order-1", "NSE_EQ|X", 100.0, 10, 0.05, "ACC"));
        feed.listener.onPrice(140.0, 1L);
        assertTrue(store.saved.get(0).getNextMilestoneIndex() > 0);

        RecordingNotifier notifier = new RecordingNotifier();
        FakeFeed resumedFeed = new FakeFeed();
        monitor(new FakeOrderFillFeed(), resumedFeed, notifier, store);
        notifier.messages.clear();

        resumedFeed.listener.onPrice(140.0, 2L); // same level, nothing new

        assertFalse(notifier.anyContains("net ("), () -> "re-fired milestones: " + notifier.messages);
    }

    @Test
    void shouldRestoreMonitorMode() {

        MemoryStore store = new MemoryStore();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        monitor(fills, new FakeFeed(), new RecordingNotifier(), store);

        fills.listener.onFill(new TradeFillEvent("order-1", "NSE_EQ|X", 100.0, 10, 0.05, "ACC"));
        assertEquals(MonitorMode.MANAGED.name(), store.saved.get(0).getMonitorMode());

        RecordingNotifier notifier = new RecordingNotifier();
        monitor(new FakeOrderFillFeed(), new FakeFeed(), notifier, store).onStatusRequested();

        assertTrue(notifier.anyContains("MANAGED"));
    }

    /** Closed trades must not come back on the next start. */
    @Test
    void shouldNotPersistClosedTrades() {

        MemoryStore store = new MemoryStore();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        TradeMonitor monitor = monitor(fills, new FakeFeed(), new RecordingNotifier(), store);

        fills.listener.onFill(new TradeFillEvent("order-1", "NSE_EQ|X", 100.0, 10, 0.05, "ACC"));
        monitor.onExit("order-1");

        assertTrue(store.saved.isEmpty(), () -> "closed trade persisted: " + store.saved.size());
    }

    /**
     * This system cannot know whether the position is still open at the
     * broker -- it may have been closed by hand while the process was down.
     */
    @Test
    void shouldWarnThatResumedTradesNeedChecking() {

        MemoryStore store = new MemoryStore();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        monitor(fills, new FakeFeed(), new RecordingNotifier(), store);
        fills.listener.onFill(new TradeFillEvent("order-1", "NSE_EQ|X", 100.0, 10, 0.05, "ACC"));

        RecordingNotifier notifier = new RecordingNotifier();
        monitor(new FakeOrderFillFeed(), new FakeFeed(), notifier, store);

        assertTrue(notifier.anyContains("Resumed 1 trade"));
        assertTrue(notifier.anyContains("Check these are still open"));
    }

    @Test
    void shouldSaySilenceWhenThereIsNothingToResume() {

        RecordingNotifier notifier = new RecordingNotifier();

        monitor(new FakeOrderFillFeed(), new FakeFeed(), notifier, new MemoryStore());

        assertTrue(notifier.messages.isEmpty(), () -> notifier.messages.toString());
    }

    // --- the JSON store itself ---

    @Test
    void jsonStoreShouldRoundTripThroughAFile(@TempDir Path dir) {

        JsonTradeStore store = new JsonTradeStore(dir.resolve("open-trades.json"));

        TradeSnapshot snapshot = new TradeSnapshot();
        snapshot.setOrderId("order-1");
        snapshot.setInstrumentKey("NSE_FO|45148");
        snapshot.setDisplaySymbol("NIFTY 25000 CE 18 AUG 26");
        snapshot.setEntryPrice(55.30);
        snapshot.setBasePrice(55.49);
        snapshot.setQuantity(845);
        snapshot.setCurrentStopLoss(33.18);
        snapshot.setPhase("PHASE_2");
        snapshot.setLadderName("OPTIONS");
        snapshot.setMonitorMode("MANAGED");
        snapshot.setNextMilestoneIndex(3);

        store.save(List.of(snapshot));
        List<TradeSnapshot> loaded = store.load();

        assertEquals(1, loaded.size());
        assertEquals("NIFTY 25000 CE 18 AUG 26", loaded.get(0).getDisplaySymbol());
        assertEquals(55.49, loaded.get(0).getBasePrice(), 0.0001);
        assertEquals(3, loaded.get(0).getNextMilestoneIndex());
        assertEquals("OPTIONS", loaded.get(0).getLadderName());
    }

    @Test
    void jsonStoreShouldReturnNothingWhenTheFileIsAbsent(@TempDir Path dir) {
        assertTrue(new JsonTradeStore(dir.resolve("missing.json")).load().isEmpty());
    }

    /**
     * Starting with no memory is bad; refusing to start is worse, because
     * then nothing is watching the positions either.
     */
    @Test
    void jsonStoreShouldSurviveACorruptFile(@TempDir Path dir) throws Exception {

        Path file = dir.resolve("open-trades.json");
        java.nio.file.Files.writeString(file, "{ this is not json");

        assertTrue(new JsonTradeStore(file).load().isEmpty());
    }
}
