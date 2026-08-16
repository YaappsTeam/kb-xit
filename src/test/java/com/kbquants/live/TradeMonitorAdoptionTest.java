package com.kbquants.live;

import com.kbquants.domain.ActiveLadder;
import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.RiskSettings;
import com.kbquants.domain.TradingToken;
import com.kbquants.instrument.Instrument;
import com.kbquants.instrument.InstrumentCatalog;
import com.kbquants.instrument.InstrumentRegistry;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Positions detected at the broker are offered, not taken.
 * <p>
 * A broker's order stream carries fills for the whole account: a trade
 * punched into the mobile app, a leg of a hedge, another strategy's
 * position. Managing those uninvited would apply exit rules never meant
 * for them -- and, once real orders are on, eventually sell them.
 */
class TradeMonitorAdoptionTest {

    private static final class FakeOrderFillFeed implements OrderFillFeed {
        TradeFillListener listener;
        int credentialSignals;

        @Override
        public void start(TradeFillListener listener) {
            this.listener = listener;
        }

        @Override
        public void stop() {
        }

        @Override
        public void onCredentialAvailable() {
            credentialSignals++;
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

    private record Fixture(TradeMonitor monitor, FakeOrderFillFeed fills, RecordingNotifier notifier) {}

    private static Fixture fixture(boolean requireAdoption) {
        return fixture(requireAdoption, Optional.empty());
    }

    private static Fixture fixture(boolean requireAdoption, Optional<InstrumentCatalog> catalog) {
        RecordingNotifier notifier = new RecordingNotifier();
        FakeOrderFillFeed fills = new FakeOrderFillFeed();
        TradeMonitor monitor = new TradeMonitor(fills, fill -> new FakeFeed(), notifier,
                new LiteralTrackRequestResolver(), new ActiveLadder(MilestoneLadder.equityLadder()),
                new EstimatedChargesService(), new RiskSettings(0), new NoOpTradeStore(),
                new PaperExitOrderPlacer(), new TradingToken(ZoneId.of("Asia/Kolkata")), requireAdoption,
                new NoOpPositionQuery(), catalog);
        return new Fixture(monitor, fills, notifier);
    }

    /** A catalog whose only known instrument is NSE_EQ|X -- everything else is out of scope. */
    private static InstrumentCatalog catalogKnowingOnly(String instrumentKey) {
        Instrument known = new Instrument(instrumentKey, instrumentKey, "NSE_FO", "CE", "NIFTY", 65, 1800, 0.05);
        return new InstrumentCatalog(new InstrumentRegistry(List.of(known)));
    }

    private static void detect(Fixture f, String orderId) {
        detect(f, orderId, "NSE_EQ|X");
    }

    private static void detect(Fixture f, String orderId, String instrumentKey) {
        f.fills().listener.onFill(new TradeFillEvent(orderId, instrumentKey, 100.0, 10, 0.05, "ACC"));
    }

    @Test
    void aDetectedFillShouldBeOfferedNotManaged() {

        Fixture f = fixture(true);

        detect(f, "broker-1");

        assertTrue(f.notifier().anyContains("NOT being managed"));

        f.notifier().messages.clear();
        f.monitor().onStatusRequested();
        assertEquals("No active trades", f.notifier().last(), "must not manage it uninvited");
    }

    @Test
    void theOfferShouldCarryAcceptAndDeclineButtons() {

        Fixture f = fixture(true);

        detect(f, "broker-1");

        LinkedHashMap<String, String> choices = f.notifier().choices.get(0);
        assertTrue(choices.containsValue("adopt:broker-1"));
        assertTrue(choices.containsValue("ignore:broker-1"));
    }

    @Test
    void adoptingShouldStartManagingIt() {

        Fixture f = fixture(true);
        detect(f, "broker-1");
        f.notifier().messages.clear();

        f.monitor().onAdoptRequested("broker-1");

        assertTrue(f.notifier().anyContains("now tracking"));
        f.notifier().messages.clear();
        f.monitor().onStatusRequested();
        assertTrue(f.notifier().last().contains("broker-1"));
    }

    @Test
    void ignoringShouldLeaveItAlone() {

        Fixture f = fixture(true);
        detect(f, "broker-1");
        f.notifier().messages.clear();

        f.monitor().onIgnoreRequested("broker-1");

        assertTrue(f.notifier().anyContains("left alone"));
        f.notifier().messages.clear();
        f.monitor().onStatusRequested();
        assertEquals("No active trades", f.notifier().last());
    }

    /** A reconnect replays fills; a declined one must not be re-offered. */
    @Test
    void anIgnoredFillShouldNotBeOfferedAgain() {

        Fixture f = fixture(true);
        detect(f, "broker-1");
        f.monitor().onIgnoreRequested("broker-1");
        f.notifier().messages.clear();

        detect(f, "broker-1");

        assertTrue(f.notifier().messages.isEmpty(), () -> f.notifier().messages.toString());
    }

    @Test
    void theSameFillShouldNotBeOfferedTwice() {

        Fixture f = fixture(true);

        detect(f, "broker-1");
        detect(f, "broker-1");

        assertEquals(1, f.notifier().choices.size());
    }

    @Test
    void adoptingSomethingUnknownShouldSaySo() {

        Fixture f = fixture(true);

        f.monitor().onAdoptRequested("never-seen");

        assertTrue(f.notifier().last().contains("Nothing pending"));
    }

    @Test
    void shouldListPendingPositions() {

        Fixture f = fixture(true);
        detect(f, "broker-1");
        detect(f, "broker-2");
        f.notifier().messages.clear();

        f.monitor().onPendingAdoptionsRequested();

        assertEquals(2, f.notifier().choices.get(0).size());
    }

    @Test
    void shouldSaySoWhenNothingIsWaiting() {

        Fixture f = fixture(true);

        f.monitor().onPendingAdoptionsRequested();

        assertTrue(f.notifier().last().contains("No positions waiting"));
    }

    /**
     * Pausing refuses the adoption but keeps the offer. Consuming it on the
     * way in would lose a real position with no way to get it back.
     */
    @Test
    void adoptingWhilePausedShouldRefuseAndKeepTheOffer() {

        Fixture f = fixture(true);
        detect(f, "broker-1");
        f.monitor().onAdoptionPaused(true);
        f.notifier().messages.clear();

        f.monitor().onAdoptRequested("broker-1");

        assertTrue(f.notifier().anyContains("adoption is paused"));
        f.monitor().onStatusRequested();
        assertFalse(f.notifier().anyContains("now tracking"));

        f.monitor().onAdoptionPaused(false);
        f.monitor().onAdoptRequested("broker-1");
        assertTrue(f.notifier().anyContains("now tracking"), "the offer must survive the pause");
    }

    /** With the gate off, a fill is managed directly, as /track does. */
    @Test
    void withoutTheGateAFillShouldBeManagedDirectly() {

        Fixture f = fixture(false);

        detect(f, "broker-1");

        assertTrue(f.notifier().anyContains("now tracking"));
        assertFalse(f.notifier().anyContains("NOT being managed"));
    }

    /**
     * The order stream cannot connect without the daily token, so it waits
     * at startup and is told when one arrives.
     */
    @Test
    void supplyingATokenShouldTellTheFeed() {

        Fixture f = fixture(true);
        assertEquals(0, f.fills().credentialSignals);

        f.monitor().onTokenProvided("a-token");

        assertEquals(1, f.fills().credentialSignals);
    }

    // ---- instrument-scope enforcement (PRODUCT_REQUIREMENTS.md F9) ----

    /**
     * The whole point of this scope check: a broker's fill stream carries
     * the entire account, and this deployment currently only manages NIFTY
     * options. A detected equity/other-underlying position must never be
     * offered for adoption, regardless of how tempting "just take it" would
     * be for a trader who forgot what this system currently covers.
     */
    @Test
    void aDetectedFillOutsideTodaysScopeShouldNotBeOffered() {

        Fixture f = fixture(true, Optional.of(catalogKnowingOnly("NSE_FO|IN_SCOPE")));

        detect(f, "broker-1", "NSE_EQ|OUT_OF_SCOPE");

        assertTrue(f.notifier().messages.isEmpty(), () -> f.notifier().messages.toString());
        f.monitor().onPendingAdoptionsRequested();
        assertTrue(f.notifier().last().contains("No positions waiting"));
    }

    @Test
    void aDetectedFillInsideTodaysScopeShouldStillBeOfferedNormally() {

        Fixture f = fixture(true, Optional.of(catalogKnowingOnly("NSE_FO|IN_SCOPE")));

        detect(f, "broker-1", "NSE_FO|IN_SCOPE");

        assertTrue(f.notifier().anyContains("NOT being managed"));
    }

    /**
     * The scope check and the adoption-consent question are independent --
     * an out-of-scope fill must not slip through even with the consent gate
     * off, the same way an unknown /track symbol is refused regardless of
     * any other setting.
     */
    @Test
    void withoutTheGateAnOutOfScopeFillShouldStillBeRefused() {

        Fixture f = fixture(false, Optional.of(catalogKnowingOnly("NSE_FO|IN_SCOPE")));

        detect(f, "broker-1", "NSE_EQ|OUT_OF_SCOPE");

        assertTrue(f.notifier().messages.isEmpty(), () -> f.notifier().messages.toString());
        f.monitor().onStatusRequested();
        assertEquals("No active trades", f.notifier().last());
    }

    @Test
    void withoutTheGateAnInScopeFillShouldStillBeManagedDirectly() {

        Fixture f = fixture(false, Optional.of(catalogKnowingOnly("NSE_FO|IN_SCOPE")));

        detect(f, "broker-1", "NSE_FO|IN_SCOPE");

        assertTrue(f.notifier().anyContains("now tracking"));
    }

    /** No catalog supplied (simulated mode) means nothing is out of scope. */
    @Test
    void withNoCatalogEveryInstrumentShouldBeInScope() {

        Fixture f = fixture(true, Optional.empty());

        detect(f, "broker-1", "NSE_EQ|ANYTHING");

        assertTrue(f.notifier().anyContains("NOT being managed"));
    }
}
