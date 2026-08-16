package com.kbquants.live;

import com.kbquants.domain.ActiveLadder;

import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.MilestoneSets;
import com.kbquants.domain.MonitorMode;
import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.Phase;
import com.kbquants.domain.RiskSettings;
import com.kbquants.domain.TradeContext;
import com.kbquants.domain.TradingToken;
import com.kbquants.engine.ExitEngine;
import com.kbquants.instrument.InstrumentCatalog;
import com.kbquants.notification.Notifier;
import com.kbquants.notification.ProfitMilestoneTracker;
import com.kbquants.notification.TelegramCommandHandler;
import com.kbquants.notification.TelegramCommandListener;
import com.kbquants.session.TrackRequest;
import com.kbquants.session.TrackRequestResolver;
import com.kbquants.session.ChargesService;
import com.kbquants.session.EstimatedChargesService;
import com.kbquants.session.MarketDataFeed;
import com.kbquants.session.TradeCost;
import com.kbquants.session.OrderFillFeed;
import com.kbquants.session.ExitOrderPlacer;
import com.kbquants.session.ExitReason;
import com.kbquants.session.PaperExitOrderPlacer;
import com.kbquants.session.TradeFillEvent;
import com.kbquants.session.TradeSnapshot;
import com.kbquants.session.TradeStore;
import com.kbquants.session.NoOpTradeStore;
import com.kbquants.session.BrokerPosition;
import com.kbquants.session.NoOpPositionQuery;
import com.kbquants.session.PositionQuery;
import com.kbquants.session.PositionQueryException;
import com.kbquants.session.PositionReconciliation;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Central live-trading orchestrator. Accepts trade invocations from any
 * OrderFillFeed (a real broker, or Telegram's /track command in paper mode --
 * see TelegramCommandListener), watches each trade's live price via a
 * broker-agnostic MarketDataFeed, and on every tick runs both the full
 * ExitEngine (hard safety, phase transitions, ownership locks) and the
 * profit-milestone notifier -- both driven by the same MilestoneLadder.
 * <p>
 * A trade closes either automatically (price reaches the current
 * stop-loss) or on a manual /exit / /exit all command. Either way the
 * trade is marked closed and no further price ticks are processed for it.
 * <p>
 * basePrice is the <b>cost-inclusive breakeven</b>, not the entry price:
 * capital is only preserved once brokerage, STT, exchange charges, GST and
 * stamp duty are covered, so that is the price PHASE_2 protects and the
 * point every reported percentage is measured from. This differs from the
 * simulation/backtest default of entry x 2.0, which is a research
 * parameter, not a live trading default. See IMPLEMENTATION_PLAN.md.
 */
@Slf4j
public class TradeMonitor implements TelegramCommandListener {

    private final Notifier notifier;
    private final Function<TradeFillEvent, MarketDataFeed> feedFactory;
    private final TrackRequestResolver trackRequestResolver;
    private final ChargesService chargesService;
    private final Map<String, ActiveTrade> activeTrades = new ConcurrentHashMap<>();

    /**
     * The set new trades will use, shared with position sizing so both see
     * the same choice. Open trades keep the ladder they were opened with --
     * their ExitEngine and milestone tracker are built from it at fill time.
     */
    private final ActiveLadder activeLadder;

    /** Runs alongside the broker's quote so the model can be checked against it. */
    private final ChargesService referenceCharges = new EstimatedChargesService();

    /**
     * Whether newly arriving trades are taken on at all.
     * <p>
     * Matters most once a real OrderFillFeed is wired in: that streams
     * fills for the whole account, including positions this system was
     * never meant to touch. Until then only /track invocations arrive, so
     * this is a safety valve waiting for Phase 3.
     */
    private volatile boolean adoptingNewTrades = true;

    /** Shared with position sizing, so /risk applies to the next trade. */
    private final RiskSettings riskSettings;

    /** Where open trades are kept so a restart does not lose them. */
    private final TradeStore tradeStore;

    /**
     * Whether fills arriving from the feed must be adopted explicitly.
     * <p>
     * A broker's order stream carries fills for the <b>whole account</b>:
     * a trade punched into the mobile app, a leg of a hedge, a position
     * from another strategy. Managing those uninvited would apply exit
     * rules never meant for them, so with this on a detected fill is
     * offered and waits.
     */
    private final boolean requireExplicitAdoption;

    /** Detected fills waiting for a yes or no. */
    private final Map<String, TradeFillEvent> pendingAdoptions = new ConcurrentHashMap<>();

    /** Retained so it can be told when the daily token arrives. */
    private final OrderFillFeed orderFillFeed;

    /** Places the sell order that actually closes a position. */
    private final ExitOrderPlacer exitOrderPlacer;

    /**
     * Asks the broker what is actually open, so a trade closed by hand --
     * or while this was down -- does not go on being managed.
     */
    private final PositionQuery positionQuery;

    /**
     * The daily order-placement token, supplied at runtime via /token.
     * Held here because the Telegram control plane is where it arrives.
     */
    private final TradingToken tradingToken;

    /**
     * Today's tracked NIFTY instrument window, checked against a detected
     * (not a /track'd) fill's instrument key. Empty when there is nothing
     * to check against -- simulated mode has no catalog, and tests that
     * don't care about scope enforcement don't need to supply one.
     * <p>
     * /track never needs this: {@link TrackRequestResolver} (in live mode,
     * {@code InstrumentAwareTrackRequestResolver}) already resolves only
     * against this same catalog, so an out-of-scope symbol is rejected
     * before {@link #onFill} is ever reached. Detected fills bypass the
     * resolver entirely -- they arrive as a raw {@link TradeFillEvent}
     * from the broker's own order stream -- so this is the one path that
     * needs its own check. See PRODUCT_REQUIREMENTS.md F9.
     */
    private final Optional<InstrumentCatalog> instrumentCatalog;

    /**
     * Order ids already taken on, kept after the trade itself is dropped.
     * <p>
     * activeTrades used to double as the duplicate-fill guard, which is why
     * closed trades were never removed from it -- and why it grew for the
     * life of the process, holding an engine, a tracker and a cost record
     * per trade ever taken. Ids alone are cheap, so the guard survives
     * while the trade does not.
     * <p>
     * Bounded: a long-running process should not accumulate ids forever
     * either, and a fill replayed thousands of trades later is not a
     * duplicate worth guarding against.
     */
    private final Set<String> handledOrderIds = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_REMEMBERED_ORDER_IDS;
                }
            }));

    private static final int MAX_REMEMBERED_ORDER_IDS = 5_000;

    /**
     * For log correlation only (MDC, see CODING_STANDARDS.md and {@link
     * #accountId()}) -- nothing in this class branches on it. Defaults to
     * "unknown" for the many constructor overloads below that predate
     * multi-account support and don't supply one; every real deployment
     * path (see {@code Main#buildTradeMonitor}) does.
     */
    private final String accountId;

    public TradeMonitor(OrderFillFeed orderFillFeed, Function<TradeFillEvent, MarketDataFeed> feedFactory,
                        Notifier notifier, TrackRequestResolver trackRequestResolver) {
        this(orderFillFeed, feedFactory, notifier, trackRequestResolver, MilestoneLadder.defaultLadder(),
                new EstimatedChargesService());
    }

    public TradeMonitor(OrderFillFeed orderFillFeed, Function<TradeFillEvent, MarketDataFeed> feedFactory,
                        Notifier notifier, TrackRequestResolver trackRequestResolver, MilestoneLadder ladder) {
        this(orderFillFeed, feedFactory, notifier, trackRequestResolver, ladder, new EstimatedChargesService());
    }

    public TradeMonitor(OrderFillFeed orderFillFeed, Function<TradeFillEvent, MarketDataFeed> feedFactory,
                         Notifier notifier, TrackRequestResolver trackRequestResolver, MilestoneLadder ladder,
                         ChargesService chargesService) {
        this(orderFillFeed, feedFactory, notifier, trackRequestResolver, new ActiveLadder(ladder), chargesService);
    }

    public TradeMonitor(OrderFillFeed orderFillFeed, Function<TradeFillEvent, MarketDataFeed> feedFactory,
                         Notifier notifier, TrackRequestResolver trackRequestResolver, ActiveLadder activeLadder,
                         ChargesService chargesService) {
        this(orderFillFeed, feedFactory, notifier, trackRequestResolver, activeLadder, chargesService,
                new RiskSettings(0));
    }

    public TradeMonitor(OrderFillFeed orderFillFeed, Function<TradeFillEvent, MarketDataFeed> feedFactory,
                         Notifier notifier, TrackRequestResolver trackRequestResolver, ActiveLadder activeLadder,
                         ChargesService chargesService, RiskSettings riskSettings) {
        this(orderFillFeed, feedFactory, notifier, trackRequestResolver, activeLadder, chargesService,
                riskSettings, new NoOpTradeStore());
    }

    public TradeMonitor(OrderFillFeed orderFillFeed, Function<TradeFillEvent, MarketDataFeed> feedFactory,
                         Notifier notifier, TrackRequestResolver trackRequestResolver, ActiveLadder activeLadder,
                         ChargesService chargesService, RiskSettings riskSettings, TradeStore tradeStore) {
        this(orderFillFeed, feedFactory, notifier, trackRequestResolver, activeLadder, chargesService,
                riskSettings, tradeStore, new PaperExitOrderPlacer());
    }

    public TradeMonitor(OrderFillFeed orderFillFeed, Function<TradeFillEvent, MarketDataFeed> feedFactory,
                         Notifier notifier, TrackRequestResolver trackRequestResolver, ActiveLadder activeLadder,
                         ChargesService chargesService, RiskSettings riskSettings, TradeStore tradeStore,
                         ExitOrderPlacer exitOrderPlacer) {
        this(orderFillFeed, feedFactory, notifier, trackRequestResolver, activeLadder, chargesService,
                riskSettings, tradeStore, exitOrderPlacer, new TradingToken(java.time.ZoneId.of("Asia/Kolkata")));
    }

    public TradeMonitor(OrderFillFeed orderFillFeed, Function<TradeFillEvent, MarketDataFeed> feedFactory,
                         Notifier notifier, TrackRequestResolver trackRequestResolver, ActiveLadder activeLadder,
                         ChargesService chargesService, RiskSettings riskSettings, TradeStore tradeStore,
                         ExitOrderPlacer exitOrderPlacer, TradingToken tradingToken) {
        this(orderFillFeed, feedFactory, notifier, trackRequestResolver, activeLadder, chargesService,
                riskSettings, tradeStore, exitOrderPlacer, tradingToken, false);
    }

    public TradeMonitor(OrderFillFeed orderFillFeed, Function<TradeFillEvent, MarketDataFeed> feedFactory,
                         Notifier notifier, TrackRequestResolver trackRequestResolver, ActiveLadder activeLadder,
                         ChargesService chargesService, RiskSettings riskSettings, TradeStore tradeStore,
                         ExitOrderPlacer exitOrderPlacer, TradingToken tradingToken,
                         boolean requireExplicitAdoption) {
        this(orderFillFeed, feedFactory, notifier, trackRequestResolver, activeLadder, chargesService,
                riskSettings, tradeStore, exitOrderPlacer, tradingToken, requireExplicitAdoption,
                new NoOpPositionQuery());
    }

    /**
     * Takes the ActiveLadder rather than a ladder so that position sizing,
     * which needs the same hard stop, cannot drift out of step when the
     * user switches sets.
     */
    public TradeMonitor(OrderFillFeed orderFillFeed, Function<TradeFillEvent, MarketDataFeed> feedFactory,
                         Notifier notifier, TrackRequestResolver trackRequestResolver, ActiveLadder activeLadder,
                         ChargesService chargesService, RiskSettings riskSettings, TradeStore tradeStore,
                         ExitOrderPlacer exitOrderPlacer, TradingToken tradingToken,
                         boolean requireExplicitAdoption, PositionQuery positionQuery) {
        this(orderFillFeed, feedFactory, notifier, trackRequestResolver, activeLadder, chargesService,
                riskSettings, tradeStore, exitOrderPlacer, tradingToken, requireExplicitAdoption, positionQuery,
                Optional.empty());
    }

    /**
     * @param instrumentCatalog  today's tracked instrument window, checked
     *                           against detected (not /track'd) fills --
     *                           see the field Javadoc. Empty when there is
     *                           nothing to check against.
     */
    public TradeMonitor(OrderFillFeed orderFillFeed, Function<TradeFillEvent, MarketDataFeed> feedFactory,
                         Notifier notifier, TrackRequestResolver trackRequestResolver, ActiveLadder activeLadder,
                         ChargesService chargesService, RiskSettings riskSettings, TradeStore tradeStore,
                         ExitOrderPlacer exitOrderPlacer, TradingToken tradingToken,
                         boolean requireExplicitAdoption, PositionQuery positionQuery,
                         Optional<InstrumentCatalog> instrumentCatalog) {
        this(orderFillFeed, feedFactory, notifier, trackRequestResolver, activeLadder, chargesService,
                riskSettings, tradeStore, exitOrderPlacer, tradingToken, requireExplicitAdoption, positionQuery,
                instrumentCatalog, "unknown");
    }

    /**
     * @param accountId  this account's id, for log correlation only -- see
     *                   the field Javadoc
     */
    public TradeMonitor(OrderFillFeed orderFillFeed, Function<TradeFillEvent, MarketDataFeed> feedFactory,
                         Notifier notifier, TrackRequestResolver trackRequestResolver, ActiveLadder activeLadder,
                         ChargesService chargesService, RiskSettings riskSettings, TradeStore tradeStore,
                         ExitOrderPlacer exitOrderPlacer, TradingToken tradingToken,
                         boolean requireExplicitAdoption, PositionQuery positionQuery,
                         Optional<InstrumentCatalog> instrumentCatalog, String accountId) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.instrumentCatalog = Objects.requireNonNull(instrumentCatalog, "instrumentCatalog must not be null");
        this.positionQuery = Objects.requireNonNull(positionQuery, "positionQuery must not be null");
        this.requireExplicitAdoption = requireExplicitAdoption;
        this.tradingToken = Objects.requireNonNull(tradingToken, "tradingToken must not be null");
        this.exitOrderPlacer = Objects.requireNonNull(exitOrderPlacer, "exitOrderPlacer must not be null");
        this.tradeStore = Objects.requireNonNull(tradeStore, "tradeStore must not be null");
        this.riskSettings = Objects.requireNonNull(riskSettings, "riskSettings must not be null");
        this.chargesService = Objects.requireNonNull(chargesService, "chargesService must not be null");
        this.feedFactory = Objects.requireNonNull(feedFactory, "feedFactory must not be null");
        this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
        this.trackRequestResolver = Objects.requireNonNull(trackRequestResolver, "trackRequestResolver must not be null");
        this.activeLadder = Objects.requireNonNull(activeLadder, "activeLadder must not be null");
        this.orderFillFeed = Objects.requireNonNull(orderFillFeed, "orderFillFeed must not be null");
        restore();
        orderFillFeed.start(this::onDetectedFillWithLogContext);
    }

    @Override
    public String accountId() {
        return accountId;
    }

    /**
     * {@link #onDetectedFill} entered from the broker's own fill stream,
     * not a Telegram command -- so unlike every {@link TelegramCommandListener}
     * method (MDC set once centrally by {@code TelegramCommandHandler}'s
     * dispatch), this path has to set its own account/trade context for
     * the log lines it produces.
     */
    private void onDetectedFillWithLogContext(TradeFillEvent fill) {
        MDC.put("accountId", accountId);
        MDC.put("orderId", fill.getOrderId());
        try {
            onDetectedFill(fill);
        } finally {
            MDC.remove("orderId");
            MDC.remove("accountId");
        }
    }

    /**
     * A fill arriving from the feed rather than from /track.
     * <p>
     * With explicit adoption on, this only offers: the alternative is
     * silently managing whatever the account happens to trade, which is
     * how a hedge leg or somebody else's strategy ends up with this
     * system's exit rules applied to it.
     */
    void onDetectedFill(TradeFillEvent fill) {

        // Out-of-scope instruments are never offered, adopted or auto-adopted --
        // checked ahead of the adoption-consent question below, since scope
        // and consent are independent concerns. No Telegram notification: the
        // account may routinely trade unrelated instruments this deployment
        // was never meant to touch, and flagging each one would just be noise
        // on top of what WATCH_BROKER_FILLS already warns about.
        if (isOutOfScope(fill)) {
            log.info("Ignoring a detected fill outside today's tracked instrument scope: orderId={} instrument={}",
                    fill.getOrderId(), fill.getInstrumentKey());
            return;
        }

        if (!requireExplicitAdoption) {
            onFill(fill);
            return;
        }

        if (activeTrades.containsKey(fill.getOrderId()) || handledOrderIds.contains(fill.getOrderId())) {
            return;
        }
        if (pendingAdoptions.putIfAbsent(fill.getOrderId(), fill) != null) {
            return;
        }

        log.info("Detected an unmanaged fill: orderId={} instrument={} qty={}",
                fill.getOrderId(), fill.getInstrumentKey(), fill.getFilledQuantity());

        LinkedHashMap<String, String> choices = new LinkedHashMap<>();
        String token = TelegramCommandHandler.ADOPT_CALLBACK_PREFIX + fill.getOrderId();
        if (TelegramCommandHandler.fitsCallbackData(token)) {
            choices.put("Manage the exit of this", token);
            choices.put("Leave it alone", TelegramCommandHandler.IGNORE_CALLBACK_PREFIX + fill.getOrderId());
        }

        offerAdoption(fill, choices);
    }

    /**
     * True when a catalog exists to check against and the fill's instrument
     * isn't in it. No catalog (simulated mode, or a test that didn't supply
     * one) means nothing to check against, so nothing is out of scope --
     * this must never itself become a reason to refuse a fill.
     */
    private boolean isOutOfScope(TradeFillEvent fill) {
        return instrumentCatalog
                .map(catalog -> catalog.registry().resolve(fill.getInstrumentKey()).isEmpty())
                .orElse(false);
    }

    private void offerAdoption(TradeFillEvent fill, LinkedHashMap<String, String> choices) {

        String prompt = String.format(
                "New position detected at your broker: %s x%d at %.2f (orderId=%s).%n"
                        + "It is NOT being managed. Adopt it only if you want this system running its exit rules on it.",
                fill.getDisplaySymbol(), fill.getFilledQuantity(), fill.getAveragePrice(), fill.getOrderId());

        if (choices.isEmpty()) {
            notifier.send(prompt + System.lineSeparator() + "Send /adopt " + fill.getOrderId() + " to take it on.");
        } else {
            notifier.sendChoices(prompt, choices);
        }
    }

    @Override
    public void onAdoptRequested(String orderId) {

        TradeFillEvent fill = pendingAdoptions.get(orderId);
        if (fill == null) {
            notifier.send("Nothing pending with orderId=" + orderId
                    + " — it may have been adopted, ignored, or never detected.");
            return;
        }

        // Paused is checked before the offer is consumed. onFill refuses
        // while paused, and dropping the offer on the way in would lose a
        // real position with no way to get it back.
        if (!adoptingNewTrades) {
            notifier.send(fill.getDisplaySymbol() + ": adoption is paused (/resume, then /adopt "
                    + orderId + "). Still waiting.");
            return;
        }

        pendingAdoptions.remove(orderId);
        log.info("Adopting detected fill orderId={}", orderId);
        onFill(fill);
    }

    @Override
    public void onIgnoreRequested(String orderId) {

        TradeFillEvent fill = pendingAdoptions.remove(orderId);
        if (fill == null) {
            notifier.send("Nothing pending with orderId=" + orderId);
            return;
        }
        // Remembered so the same fill is not offered again on a reconnect.
        handledOrderIds.add(orderId);
        log.info("Ignoring detected fill orderId={}", orderId);
        notifier.send(fill.getDisplaySymbol() + ": left alone — this system will not touch it.");
    }

    @Override
    public void onPendingAdoptionsRequested() {

        if (pendingAdoptions.isEmpty()) {
            notifier.send("No positions waiting to be adopted");
            return;
        }

        LinkedHashMap<String, String> choices = new LinkedHashMap<>();
        for (TradeFillEvent fill : pendingAdoptions.values()) {
            String token = TelegramCommandHandler.ADOPT_CALLBACK_PREFIX + fill.getOrderId();
            if (TelegramCommandHandler.fitsCallbackData(token)) {
                choices.put(String.format("%s x%d at %.2f",
                        fill.getDisplaySymbol(), fill.getFilledQuantity(), fill.getAveragePrice()), token);
            }
        }

        if (choices.isEmpty()) {
            notifier.send("Positions waiting, but their ids are too long for buttons — use /adopt <orderId>");
            return;
        }
        notifier.sendChoices("Positions detected but not managed:", choices);
    }

    void onFill(TradeFillEvent fill) {

        // The paused check sits here rather than at the feed so that a
        // broker fill and a Telegram invocation are treated identically --
        // and so nothing is silently adopted while paused.
        if (!adoptingNewTrades) {
            log.info("Adoption paused, ignoring fill: orderId={} instrument={}",
                    fill.getOrderId(), fill.getInstrumentKey());
            notifier.send(String.format("%s: not tracked — adoption is paused (/resume to re-enable)",
                    fill.getInstrumentKey()));
            return;
        }

        // Snapshotted so a set change mid-fill cannot leave the engine and
        // the tracker on different ladders.
        MilestoneLadder ladderForTrade = activeLadder.get();

        // basePrice is the cost-inclusive breakeven, not the entry price:
        // capital is only genuinely preserved once brokerage, STT, exchange
        // charges, GST and stamp duty are covered. Everything downstream --
        // phase transitions, milestone notifications, ownership locks --
        // measures from here, so every percentage reported is net.
        TradeCost cost = chargesService.roundTripCost(
                fill.getInstrumentKey(), fill.getAveragePrice(), fill.getFilledQuantity());
        double breakeven = cost.breakevenPrice(fill.getAveragePrice(), fill.getTickSize());

        TradeContext context = new TradeContext(
                fill.getOrderId(), fill.getAveragePrice(), breakeven,
                fill.getFilledQuantity(), OwnershipMode.MILESTONE);

        ActiveTrade trade = new ActiveTrade(fill, context, new ExitEngine(context, ladderForTrade),
                new ProfitMilestoneTracker(breakeven, ladderForTrade), cost, ladderForTrade);

        if (!handledOrderIds.add(fill.getOrderId())) {
            log.debug("Ignoring duplicate fill event for orderId={}", fill.getOrderId());
            return;
        }
        activeTrades.put(fill.getOrderId(), trade);

        log.info("Tracking new trade: orderId={} instrument={} entryPrice={} breakeven={} cost={}",
                fill.getOrderId(), fill.getInstrumentKey(), fill.getAveragePrice(), breakeven, cost);
        notifier.send(buildTrackingMessage(fill, cost, breakeven, ladderForTrade));

        persist();
        startFeed(trade);
    }

    /**
     * Writes the open trades out. Called after anything that changes what
     * would need restoring -- a new trade, a moved stop, a phase change, a
     * mode change, a close -- rather than on every tick, since most ticks
     * change nothing worth keeping.
     */
    private void persist() {
        tradeStore.save(openTrades().map(TradeMonitor::toSnapshot).toList());
    }

    static TradeSnapshot toSnapshot(ActiveTrade trade) {

        TradeSnapshot snapshot = new TradeSnapshot();
        snapshot.setOrderId(trade.fill.getOrderId());
        snapshot.setInstrumentKey(trade.fill.getInstrumentKey());
        snapshot.setDisplaySymbol(trade.fill.getDisplaySymbol());
        snapshot.setEntryPrice(trade.fill.getAveragePrice());
        snapshot.setQuantity(trade.fill.getFilledQuantity());
        snapshot.setTickSize(trade.fill.getTickSize());

        snapshot.setBasePrice(trade.context.getBasePrice());
        snapshot.setPhase(trade.context.getCurrentPhase().name());
        snapshot.setCurrentStopLoss(trade.context.getCurrentStopLoss());
        snapshot.setLadderName(trade.ladder.getName());
        snapshot.setLastPrice(trade.lastPrice);
        snapshot.setMonitorMode(trade.mode.name());

        snapshot.setNextMilestoneIndex(trade.tracker.getNextThresholdIndex());
        snapshot.setBreakevenReported(trade.breakevenReported);

        snapshot.setBuyCharges(trade.cost.getBuyCharges());
        snapshot.setSellCharges(trade.cost.getSellCharges());
        snapshot.setCostEstimated(trade.cost.isEstimated());

        return snapshot;
    }

    /**
     * Rebuilds trades from a previous run and starts watching them again.
     * <p>
     * The ratcheted stop is restored as-is rather than recomputed: it may
     * have been tightened well above the hard stop over the life of the
     * trade, and recomputing would silently give that protection back.
     * <p>
     * The user is told what was resumed, because this system cannot know
     * whether the position is still open at the broker -- it may have been
     * closed by hand while the process was down.
     */
    private void restore() {

        List<TradeSnapshot> saved = tradeStore.load();
        if (saved.isEmpty()) {
            return;
        }

        StringBuilder summary = new StringBuilder("Resumed " + saved.size() + " trade(s) from before the restart:");

        for (TradeSnapshot snapshot : saved) {
            try {
                ActiveTrade trade = fromSnapshot(snapshot);
                activeTrades.put(trade.fill.getOrderId(), trade);
                if (trade.mode != MonitorMode.RELEASED) {
                    startFeed(trade);
                }
                summary.append(String.format("%n  %s entry %.2f, breakeven %.2f, stop %.2f, %s [%s]",
                        trade.fill.getDisplaySymbol(), trade.fill.getAveragePrice(), trade.context.getBasePrice(),
                        trade.context.getCurrentStopLoss(), trade.context.getCurrentPhase(), trade.mode));
            } catch (Exception e) {
                log.error("Could not resume trade {}: {}", snapshot.getOrderId(), e.getMessage());
                summary.append(String.format("%n  %s could NOT be resumed (%s) — manage it yourself",
                        snapshot.getDisplaySymbol(), e.getMessage()));
            }
        }

        summary.append(System.lineSeparator())
               .append("Check these are still open at your broker — positions closed while this was down are not known here.");

        log.info("Resumed {} trade(s) from {}", saved.size(), tradeStore.getClass().getSimpleName());
        notifier.send(summary.toString());
    }

    private ActiveTrade fromSnapshot(TradeSnapshot snapshot) {

        MilestoneLadder ladder = MilestoneSets.byName(snapshot.getLadderName())
                .orElseGet(() -> {
                    log.warn("Unknown milestone set {} in saved trade {} -- falling back to the active set",
                            snapshot.getLadderName(), snapshot.getOrderId());
                    return activeLadder.get();
                });

        TradeFillEvent fill = new TradeFillEvent(snapshot.getOrderId(), snapshot.getInstrumentKey(),
                snapshot.getEntryPrice(), snapshot.getQuantity(), snapshot.getTickSize(),
                snapshot.getDisplaySymbol());

        TradeContext context = new TradeContext(snapshot.getOrderId(), snapshot.getEntryPrice(),
                snapshot.getBasePrice(), snapshot.getQuantity(), OwnershipMode.MILESTONE);
        context.setCurrentPhase(Phase.valueOf(snapshot.getPhase()));
        context.setCurrentStopLoss(snapshot.getCurrentStopLoss());

        TradeCost cost = new TradeCost(snapshot.getBuyCharges(), snapshot.getSellCharges(),
                snapshot.getEntryPrice() * snapshot.getQuantity(), snapshot.isCostEstimated());

        ActiveTrade trade = new ActiveTrade(fill, context, new ExitEngine(context, ladder),
                new ProfitMilestoneTracker(snapshot.getBasePrice(), ladder, snapshot.getNextMilestoneIndex()),
                cost, ladder);

        trade.lastPrice = snapshot.getLastPrice() > 0 ? snapshot.getLastPrice() : snapshot.getEntryPrice();
        trade.breakevenReported = snapshot.isBreakevenReported();
        trade.mode = MonitorMode.valueOf(snapshot.getMonitorMode());
        return trade;
    }

    /**
     * The feed is retained so it can be closed again. Previously it was
     * created and discarded, which left a WebSocket (or a scheduler thread)
     * open for every trade ever taken, for the life of the process.
     */
    private void startFeed(ActiveTrade trade) {

        MarketDataFeed feed = feedFactory.apply(trade.fill);
        trade.feed = feed;

        // Both callbacks fire from the feed's own thread, not through
        // Telegram dispatch, so unlike TelegramCommandListener methods
        // (MDC set once centrally) each has to set its own account/trade
        // context here.
        feed.setFailureListener(reason -> withTradeLogContext(trade, () -> reportFeedFailure(trade, reason)));
        feed.start((price, timestamp) -> withTradeLogContext(trade, () -> onPrice(trade, price)));
    }

    private void withTradeLogContext(ActiveTrade trade, Runnable action) {
        MDC.put("accountId", accountId);
        MDC.put("orderId", trade.fill.getOrderId());
        try {
            action.run();
        } finally {
            MDC.remove("orderId");
            MDC.remove("accountId");
        }
    }

    /**
     * Closing the feed is what makes a trade finished: without prices there
     * is nothing left to react to. Safe to call more than once.
     */
    private void stopFeed(ActiveTrade trade) {
        MarketDataFeed feed = trade.feed;
        trade.feed = null;
        if (feed != null) {
            feed.stop();
        }
    }

    /**
     * A dead feed means the stop-loss is no longer being enforced, and
     * nothing else would make that visible -- prices simply stop arriving
     * while the bot looks healthy. Reported once per outage so a flapping
     * connection cannot spam the chat.
     */
    private void reportFeedFailure(ActiveTrade trade, String reason) {

        if (trade.context.isClosed() || trade.mode == MonitorMode.RELEASED || trade.feedFailureReported) {
            return;
        }
        trade.feedFailureReported = true;

        log.error("Price feed problem for orderId={} instrument={}: {}",
                trade.fill.getOrderId(), trade.fill.getInstrumentKey(), reason);
        notifier.send(String.format(
                "⚠ %s: price feed dropped (%s). The stop at %.2f is NOT being enforced until it reconnects. "
                        + "Reconnection is automatic; if prices do not resume, manage this position yourself.",
                trade.fill.getDisplaySymbol(), reason, trade.context.getCurrentStopLoss()));
    }

    void onPrice(ActiveTrade trade, double currentPrice) {

        if (trade.context.isClosed() || trade.mode == MonitorMode.RELEASED) {
            return;
        }

        if (trade.feedFailureReported) {
            trade.feedFailureReported = false;
            notifier.send(String.format("%s: price feed recovered, back to %.2f — the stop is being enforced again",
                    trade.fill.getDisplaySymbol(), currentPrice));
        }

        trade.lastPrice = currentPrice;
        trade.lastPriceAt = System.currentTimeMillis();

        // Most ticks change nothing worth keeping, so the write is driven
        // by the stop ratcheting or the phase advancing rather than by
        // every price that arrives.
        double stopBefore = trade.context.getCurrentStopLoss();
        Phase phaseBefore = trade.context.getCurrentPhase();

        trade.engine.onPriceUpdate(currentPrice, trade.context);

        boolean materialChange = trade.context.getCurrentStopLoss() != stopBefore
                || trade.context.getCurrentPhase() != phaseBefore;

        if (!trade.context.isClosed() && trade.context.getCurrentStopLoss() > 0
                && currentPrice <= trade.context.getCurrentStopLoss()) {

            double stopLoss = trade.context.getCurrentStopLoss();

            // In OBSERVED mode the engine reports the breach and stops
            // there. Reported once, not on every subsequent tick below the
            // stop, since the position stays open.
            if (!trade.mode.isAutoExitAllowed()) {
                if (!trade.stopBreachReported) {
                    trade.stopBreachReported = true;
                    log.info("Stop-loss breached but not acted on (observing): orderId={} price={} stopLoss={}",
                            trade.fill.getOrderId(), currentPrice, stopLoss);
                    notifier.send(String.format(
                            "%s: stop-loss level %.2f breached at %.2f — observing only, no exit placed. Yours to act on.",
                            trade.fill.getInstrumentKey(), stopLoss, currentPrice));
                }
                return;
            }

            if (!closePosition(trade, currentPrice, ExitReason.STOP_LOSS)) {
                return;
            }
            log.info("Stop-loss hit: orderId={} instrument={} price={} stopLoss={}",
                    trade.fill.getOrderId(), trade.fill.getInstrumentKey(), currentPrice, stopLoss);
            notifier.send(String.format("%s: stop-loss hit at %.2f (sl=%.2f) — trade closed%n%s",
                    trade.fill.getInstrumentKey(), currentPrice, stopLoss, settlement(trade, currentPrice)));
            discard(trade);
            persist();
            return;
        }

        // Breakeven is its own event rather than a ladder rung: it is 0%
        // net profit by definition, and the ladder only carries positive
        // thresholds. It is the first thing worth telling the user --
        // from here on, anything gained is theirs.
        boolean breakevenJustReported = false;
        if (!trade.breakevenReported && currentPrice >= trade.context.getBasePrice()) {
            trade.breakevenReported = true;
            breakevenJustReported = true;
            notifier.send(String.format("%s: breakeven — costs of %.2f covered at %.2f",
                    trade.fill.getInstrumentKey(), trade.cost.getTotalCharges(), currentPrice));
        }

        OptionalDouble crossed = trade.tracker.checkAndAdvance(currentPrice);
        if (crossed.isPresent()) {
            notifier.send(formatMilestoneMessage(trade.fill, currentPrice, crossed.getAsDouble(), trade));
        }

        if (materialChange || crossed.isPresent() || breakevenJustReported) {
            persist();
        }
    }

    @Override
    public void onTrack(List<String> args) {

        TrackRequest request = trackRequestResolver.resolve(args);

        if (!request.isAccepted()) {
            log.info("Rejected /track {}: {}", args, request.getRejectionReason());
            notifier.send("Cannot track: " + request.getRejectionReason());
            return;
        }

        if (request.getNote() != null) {
            notifier.send(String.format("%s @ %.2f x %d — %s",
                    request.getDisplaySymbol(), request.getPrice(), request.getQuantity(), request.getNote()));
        }

        onFill(new TradeFillEvent("telegram-" + UUID.randomUUID(),
                request.getInstrumentKey(), request.getPrice(), request.getQuantity(), request.getTickSize(),
                request.getDisplaySymbol()));
    }

    @Override
    public void onExit(String orderId) {
        ActiveTrade trade = activeTrades.get(orderId);
        if (trade == null) {
            notifier.send("No active trade found for orderId=" + orderId);
            return;
        }
        forceExit(trade);
    }

    @Override
    public void onExitAll() {
        boolean anyOpen = false;
        for (ActiveTrade trade : activeTrades.values()) {
            if (!trade.context.isClosed()) {
                anyOpen = true;
                forceExit(trade);
            }
        }
        if (!anyOpen) {
            notifier.send("No active trades to exit");
        }
    }

    @Override
    public void onUnknownCommand(String command) {
        notifier.send(command + " is not a command — send /help");
    }

    @Override
    public void onHelpRequested() {
        notifier.send(TelegramCommandHandler.HELP_TEXT);
    }

    @Override
    public void onMalformedCommand(String command, String usage) {
        notifier.send(command + " needs arguments — usage: " + usage);
    }

    /**
     * Closes out at the end-of-day cutoff, so intraday positions are not
     * left for the broker to square off at whatever price it gets.
     * <p>
     * Only MANAGED trades are closed. OBSERVED means the user took the
     * trigger back, so it is told rather than acted on -- silently selling
     * a position someone had explicitly taken manual control of would be
     * the one thing that mode promises not to do. RELEASED is left alone
     * entirely.
     */
    public void onEndOfDay() {

        List<ActiveTrade> open = openTrades().toList();
        if (open.isEmpty()) {
            log.info("End-of-day cutoff reached with no open trades");
            return;
        }

        for (ActiveTrade trade : open) {

            if (trade.mode == MonitorMode.RELEASED) {
                continue;
            }

            if (!trade.mode.isAutoExitAllowed()) {
                notifier.send(String.format(
                        "⏰ %s: end-of-day cutoff reached and this trade is only being observed — no exit placed. "
                                + "Close it yourself or your broker will square it off.",
                        trade.fill.getDisplaySymbol()));
                continue;
            }

            double exitPrice = trade.lastPrice > 0 ? trade.lastPrice : trade.fill.getAveragePrice();
            trade.context.setCurrentPhase(Phase.PHASE_4);

            if (!closePosition(trade, exitPrice, ExitReason.END_OF_DAY)) {
                continue;
            }

            log.info("End-of-day exit: orderId={} instrument={} price={}",
                    trade.fill.getOrderId(), trade.fill.getInstrumentKey(), exitPrice);
            notifier.send(String.format("⏰ %s: end-of-day exit at %.2f (orderId=%s)%n%s",
                    trade.fill.getDisplaySymbol(), exitPrice, trade.fill.getOrderId(),
                    settlement(trade, exitPrice)));

            discard(trade);
        }

        persist();
    }

    @Override
    public void onExitChoicesRequested() {
        offerTradeButtons("Sell which trade?", TelegramCommandHandler.EXIT_CALLBACK_PREFIX, "Sell all open");
    }

    @Override
    public void onMonitorModeChoicesRequested(MonitorMode mode) {

        String prompt = switch (mode) {
            case RELEASED -> "Release which trade? The position stays open — only the monitoring stops.";
            case OBSERVED -> "Observe which trade? Alerts continue, automatic exits stop.";
            case MANAGED -> "Manage which trade? Automatic exits resume.";
        };

        offerTradeButtons(prompt, TelegramCommandHandler.callbackPrefixFor(mode),
                "Apply to all open trades");
    }

    /**
     * One button per open trade, labelled with enough to choose without
     * checking /status first: symbol, entry, current price and mode.
     * <p>
     * Trades whose orderId would overflow Telegram's callback_data limit
     * are listed as text instead of being offered as a button -- a
     * truncated token would act on the wrong trade.
     */
    private void offerTradeButtons(String prompt, String prefix, String allLabel) {

        List<ActiveTrade> open = openTrades().toList();
        if (open.isEmpty()) {
            notifier.send("No open trades");
            return;
        }

        LinkedHashMap<String, String> choices = new LinkedHashMap<>();
        StringBuilder untargetable = new StringBuilder();

        for (ActiveTrade trade : open) {
            String token = prefix + trade.fill.getOrderId();
            if (!TelegramCommandHandler.fitsCallbackData(token)) {
                untargetable.append(System.lineSeparator()).append("  ").append(trade.fill.getOrderId());
                continue;
            }
            choices.put(describeForButton(trade), token);
        }

        if (open.size() > 1) {
            choices.put(allLabel, prefix + "all");
        }

        if (choices.isEmpty()) {
            notifier.send(prompt + " — no trade has an id short enough for a button; use the command with an orderId:"
                    + untargetable);
            return;
        }

        notifier.sendChoices(untargetable.length() == 0
                ? prompt
                : prompt + System.lineSeparator() + "(too long for a button, type the id instead:" + untargetable + ")",
                choices);
    }

    private static String describeForButton(ActiveTrade trade) {
        return String.format("%s  %.2f → %.2f  [%s]",
                trade.fill.getDisplaySymbol(), trade.fill.getAveragePrice(), trade.lastPrice, trade.mode);
    }

    @Override
    public void onTokenStatusRequested() {
        notifier.send(tradingToken.describe());
    }

    /**
     * Deliberately does not echo the token, log it, or confirm any part of
     * it. The reply says only that one was accepted.
     */
    @Override
    public void onTokenProvided(String token) {
        try {
            tradingToken.set(token);
            log.info("Trading token supplied");

            // The broker's order stream cannot connect without one, so it
            // waits at startup and is told here instead.
            orderFillFeed.onCredentialAvailable();
            notifier.send("Token accepted — " + tradingToken.describe()
                    + System.lineSeparator()
                    + "Delete your message if it is still in the chat: it contains a live credential.");

            // The first moment the broker can be asked anything. Trades
            // restored from before the restart have been managed on faith
            // until now, so this is when that gets checked -- off the
            // command thread, which would otherwise stop answering
            // Telegram for as long as the call takes.
            if (positionQuery.isAvailable() && !activeTrades.isEmpty()) {
                Thread reconcile = new Thread(this::onReconcileRequested, "startup-reconcile");
                reconcile.setDaemon(true);
                reconcile.start();
            }
        } catch (IllegalArgumentException e) {
            notifier.send("That token looks empty — send /token <value>");
        }
    }

    /**
     * Checks what this system is managing against what the broker actually
     * holds.
     * <p>
     * Nothing is ever dropped on the strength of this. A trade the broker
     * does not report is moved to OBSERVED: the engine stops acting on it,
     * but keeps watching and reporting, and the user decides. Both possible
     * errors are real -- a position genuinely closed by hand, and one the
     * API simply did not list (a delivery holding, a settled position, a
     * bad minute at the broker) -- and dropping the trade would be
     * unrecoverable while merely standing down is not.
     * <p>
     * Acting is what makes a stale trade dangerous: an exit sized from what
     * this system remembers would sell quantity that is no longer there,
     * and selling past flat is a short.
     */
    @Override
    public void onReconcileRequested() {

        if (!positionQuery.isAvailable()) {
            notifier.send("Cannot check your broker: " + tradingToken.describe());
            return;
        }

        List<BrokerPosition> positions;
        try {
            positions = positionQuery.openPositions();
        } catch (PositionQueryException e) {
            log.warn("Reconciliation could not reach the broker: {}", e.getMessage());
            notifier.send("Could not check your broker (" + e.getMessage() + ")."
                    + System.lineSeparator() + "Nothing has been changed — trades are still managed as they were.");
            return;
        }

        List<PositionReconciliation.TrackedPosition> tracked = openTrades()
                .map(trade -> new PositionReconciliation.TrackedPosition(
                        trade.fill.getOrderId(), trade.fill.getInstrumentKey(), trade.fill.getDisplaySymbol(),
                        trade.fill.getFilledQuantity(), trade.mode == MonitorMode.MANAGED))
                .toList();

        PositionReconciliation.Report report = PositionReconciliation.compare(tracked, positions);
        applyReconciliation(report);
    }

    private void applyReconciliation(PositionReconciliation.Report report) {

        StringBuilder summary = new StringBuilder("Checked against your broker:");
        summary.append(System.lineSeparator())
               .append(String.format("  %d confirmed still open", report.getConfirmed().size()));

        for (PositionReconciliation.Discrepancy discrepancy : report.getDiscrepancies()) {

            String orderId = discrepancy.getTracked().getOrderId();
            ActiveTrade trade = activeTrades.get(orderId);
            if (trade == null) {
                continue;
            }

            summary.append(System.lineSeparator()).append("  ").append(discrepancy.describe());

            if (trade.mode == MonitorMode.MANAGED) {
                trade.mode = MonitorMode.OBSERVED;
                log.warn("Reconciliation: orderId={} is {} at the broker — stood down to OBSERVED",
                        orderId, discrepancy.getKind());
                summary.append(System.lineSeparator())
                       .append("    → stopped acting on it (now OBSERVED). /manage ").append(orderId)
                       .append(" to hand it back, /release ").append(orderId).append(" to drop it.");
            } else {
                summary.append(System.lineSeparator())
                       .append("    → already ").append(trade.mode).append("; nothing changed.");
            }
        }

        if (!report.getDiscrepancies().isEmpty()) {
            persist();
        }

        for (BrokerPosition position : report.getUntracked()) {
            summary.append(System.lineSeparator())
                   .append(String.format("  %s x%d open at your broker, not managed here",
                           position.getDisplaySymbol(), position.getQuantity()));
        }

        notifier.send(summary.toString());

        // Offered afterwards, and one message each, so the summary stays
        // readable and each offer carries its own pair of buttons.
        for (BrokerPosition position : report.getUntracked()) {
            onDetectedFillWithLogContext(toFill(position));
        }
    }

    /**
     * A position is not a fill, so the id is synthesised. Prefixed so it is
     * obvious in /status and in the buttons that this came from a
     * reconciliation rather than from an order this system saw happen.
     */
    private static TradeFillEvent toFill(BrokerPosition position) {
        return new TradeFillEvent("recon:" + position.getInstrumentKey(), position.getInstrumentKey(),
                position.getAveragePrice(), position.getQuantity(), 0, position.getDisplaySymbol());
    }

    @Override
    public void onRiskShow() {
        notifier.send(describeRisk());
    }

    @Override
    public void onRiskSet(double maxRiskPerTrade) {
        riskSettings.set(maxRiskPerTrade);
        log.info("Max risk per trade set to {}", maxRiskPerTrade);
        notifier.send("Updated — " + describeRisk() + " (applies to trades opened from now)");
    }

    /**
     * Spells out the consequence rather than just the number: with no
     * ceiling, size is capped by capital alone and a stopped-out trade
     * costs capital x the set's hard stop, which is easy to underestimate.
     */
    private String describeRisk() {

        if (!riskSettings.isEnabled()) {
            return String.format(
                    "no risk ceiling — position size is capped by capital alone, so a stop-out costs "
                            + "CAPITAL_PER_TRADE x %.0f%% on the %s set. Set one with /risk <amount>.",
                    activeLadder.get().getHardStopPercent() * 100, activeLadder.get().getName());
        }

        return String.format("max risk per trade %.0f, sized against the %s set's %.0f%% hard stop",
                riskSettings.getMaxRiskPerTrade(), activeLadder.get().getName(),
                activeLadder.get().getHardStopPercent() * 100);
    }

    @Override
    public void onAdoptionPaused(boolean paused) {

        adoptingNewTrades = !paused;
        long open = openTrades().count();

        if (paused) {
            log.info("Adoption paused; {} open trade(s) remain managed", open);
            notifier.send(open == 0
                    ? "Paused — new trades will be ignored until /resume"
                    : String.format("Paused — new trades will be ignored until /resume.%n"
                            + "%d open trade(s) are still being managed; use /release all to hand those back too.", open));
        } else {
            notifier.send("Resumed — new trades will be tracked again");
        }
    }

    @Override
    public void onMonitorModeRequested(String target, MonitorMode mode) {

        List<ActiveTrade> targets = "all".equalsIgnoreCase(target)
                ? openTrades().toList()
                : openTrades().filter(t -> t.fill.getOrderId().equals(target)).toList();

        if (targets.isEmpty()) {
            notifier.send("all".equalsIgnoreCase(target)
                    ? "No open trades to change"
                    : "No active trade found for orderId=" + target);
            return;
        }

        for (ActiveTrade trade : targets) {
            MonitorMode previous = trade.mode;
            trade.mode = mode;

            // RELEASED means stop watching, so the connection goes with it;
            // returning to a watching mode has to bring it back, or the
            // trade would sit there silently receiving nothing.
            if (mode == MonitorMode.RELEASED) {
                stopFeed(trade);
            } else if (previous == MonitorMode.RELEASED && trade.feed == null) {
                trade.feedFailureReported = false;
                startFeed(trade);
            }

            log.info("Monitor mode for orderId={} set to {} (was {})", trade.fill.getOrderId(), mode, previous);
            notifier.send(describeModeChange(trade, mode));
        }
        persist();
    }

    /**
     * Spells out what protection is being given up. Releasing a trade
     * removes the stop the engine was holding, and the user needs the
     * number to take it over.
     */
    private static String describeModeChange(ActiveTrade trade, MonitorMode mode) {

        String instrument = trade.fill.getInstrumentKey();
        double stop = trade.context.getCurrentStopLoss();

        return switch (mode) {
            case RELEASED -> String.format(
                    "%s: released — position still open, no longer monitored. The stop it was holding was %.2f; that protection is gone. (orderId=%s)",
                    instrument, stop, trade.fill.getOrderId());
            case OBSERVED -> String.format(
                    "%s: observing — milestones still reported and the stop still tracked at %.2f, but no exit will be placed. (orderId=%s)",
                    instrument, stop, trade.fill.getOrderId());
            case MANAGED -> String.format(
                    "%s: managed — exits will be placed automatically again, stop at %.2f. (orderId=%s)",
                    instrument, stop, trade.fill.getOrderId());
        };
    }

    private java.util.stream.Stream<ActiveTrade> openTrades() {
        return activeTrades.values().stream().filter(t -> !t.context.isClosed());
    }

    @Override
    public void onRefreshInstruments() {
        notifier.send(trackRequestResolver.refreshInstruments());
    }

    @Override
    public void onLadderChoicesRequested() {

        LinkedHashMap<String, String> choices = new LinkedHashMap<>();
        for (MilestoneLadder candidate : MilestoneSets.available()) {
            String label = MilestoneSets.describe(candidate)
                    + (candidate.getName().equals(activeLadder.get().getName()) ? "  ✓ active" : "");
            choices.put(label, TelegramCommandHandler.LADDER_CALLBACK_PREFIX + candidate.getName());
        }

        notifier.sendChoices("Milestone set for the next trade (active: " + activeLadder.get().getName() + ")", choices);
    }

    @Override
    public void onLadderSelected(String setName) {

        Optional<MilestoneLadder> selected = MilestoneSets.byName(setName);
        if (selected.isEmpty()) {
            notifier.send("Unknown milestone set: " + setName + " (available: " + String.join(", ", MilestoneSets.names()) + ")");
            return;
        }

        // Switching is a pre-trade decision: an open trade keeps the ladder
        // it was opened with, so allowing a change mid-flight would leave
        // "active" meaning something different from what is actually
        // managing your money.
        long openTrades = activeTrades.values().stream().filter(t -> !t.context.isClosed()).count();
        if (openTrades > 0) {
            notifier.send(String.format(
                    "Cannot change milestone set with %d trade(s) open — they keep the set they were opened with. Exit first, then choose.",
                    openTrades));
            return;
        }

        MilestoneLadder ladder = selected.get();
        if (ladder.getName().equals(activeLadder.get().getName())) {
            notifier.send(MilestoneSets.describe(ladder) + " — already active");
            return;
        }

        activeLadder.set(ladder);
        log.info("Active milestone set changed to {}", ladder.getName());
        notifier.send("Milestone set is now " + MilestoneSets.describe(ladder));
    }

    @Override
    public void onStatusRequested() {

        StringBuilder sb = new StringBuilder();
        for (ActiveTrade trade : activeTrades.values()) {
            if (trade.context.isClosed()) continue;
            sb.append(String.format("%s: entry=%.2f breakeven=%.2f current=%.2f phase=%s sl=%.2f [%s] orderId=%s%n",
                    trade.fill.getInstrumentKey(), trade.fill.getAveragePrice(), trade.context.getBasePrice(),
                    trade.lastPrice, trade.context.getCurrentPhase(), trade.context.getCurrentStopLoss(),
                    trade.mode, trade.fill.getOrderId()));
        }

        if (!adoptingNewTrades) {
            sb.append("(adoption paused — new trades ignored)").append(System.lineSeparator());
        }

        notifier.send(sb.length() == 0 ? "No active trades" : sb.toString());
    }

    /**
     * Feed state and last-tick age per open trade, plus the daily token's
     * usability -- see PRODUCT_REQUIREMENTS.md / IMPLEMENTATION_PLAN.md
     * step 4.3. Unlike /status this exists purely to answer "is anything
     * broken right now", not to show trade economics.
     */
    @Override
    public void onHealthRequested() {

        StringBuilder sb = new StringBuilder();
        for (ActiveTrade trade : activeTrades.values()) {
            if (trade.context.isClosed()) continue;
            long ageSeconds = (System.currentTimeMillis() - trade.lastPriceAt) / 1000;
            sb.append(String.format("%s: feed=%s last tick %ds ago orderId=%s%n",
                    trade.fill.getInstrumentKey(), trade.feed != null ? "attached" : "stopped",
                    ageSeconds, trade.fill.getOrderId()));
        }

        if (sb.length() == 0) {
            sb.append("No active trades").append(System.lineSeparator());
        }

        sb.append("Daily trading token: ").append(tradingToken.isUsable() ? "usable" : "not usable")
                .append(System.lineSeparator());

        notifier.send(sb.toString());
    }

    /**
     * Stops every still-open trade's feed and logs its final state, for an
     * orderly process shutdown (IMPLEMENTATION_PLAN.md step 4.3) --
     * {@code Main} calls this for every account's monitor from its
     * shutdown hook. Deliberately quiet on Telegram: a restart is routine
     * operations, not something every account needs a message about, and
     * open trades are restored from {@link #restore()} on the next start
     * regardless.
     * <p>
     * Safe with zero active trades, and safe if a feed is already stopped
     * -- {@link #stopFeed} already tolerates both.
     */
    public void shutdown() {
        openTrades().forEach(trade -> {
            stopFeed(trade);
            log.info("Shutdown: orderId={} instrument={} phase={} lastPrice={} stopLoss={}",
                    trade.fill.getOrderId(), trade.fill.getInstrumentKey(), trade.context.getCurrentPhase(),
                    trade.lastPrice, trade.context.getCurrentStopLoss());
        });
    }

    private void forceExit(ActiveTrade trade) {
        double exitPrice = trade.lastPrice > 0 ? trade.lastPrice : trade.fill.getAveragePrice();

        if (!closePosition(trade, exitPrice, ExitReason.MANUAL)) {
            return;
        }

        log.info("Force exit: orderId={} instrument={} price={}",
                trade.fill.getOrderId(), trade.fill.getInstrumentKey(), exitPrice);
        notifier.send(String.format("%s: force-exited at %.2f (orderId=%s)%n%s",
                trade.fill.getInstrumentKey(), exitPrice, trade.fill.getOrderId(), settlement(trade, exitPrice)));
        discard(trade);
        persist();
    }

    /**
     * Attempts the sell, and only treats the trade as closed if it
     * succeeded.
     * <p>
     * A rejected order means the position is still open at the broker.
     * Marking it closed would stop the engine managing a live position on
     * the strength of an order that never happened -- the stop would no
     * longer be enforced, and the user would believe they were flat. So a
     * failure is reported loudly and the trade stays under management.
     *
     * @return true if the position is now closed
     */
    private boolean closePosition(ActiveTrade trade, double exitPrice, ExitReason reason) {

        ExitOrderPlacer.Result result = exitOrderPlacer.placeExit(trade.fill, exitPrice, reason);

        // Unknown is not the same as rejected. The order may be resting at
        // the exchange, so retrying could sell a position that is already
        // gone and open a short. The engine stops acting and hands the
        // trade back rather than guessing.
        if (result.isUncertain()) {
            log.error("Exit order outcome UNKNOWN for orderId={} ({}): {}",
                    trade.fill.getOrderId(), reason, result.getDetail());
            trade.mode = MonitorMode.OBSERVED;
            notifier.send(String.format(
                    "🛑 %s: exit order outcome UNKNOWN — %s%n"
                            + "It may or may not have been sold, so nothing further will be placed automatically "
                            + "and this trade is now OBSERVED. Check your broker, then /manage it back or /release it.",
                    trade.fill.getDisplaySymbol(), result.getDetail()));
            persist();
            return false;
        }

        if (!result.isSuccessful()) {
            log.error("Exit order FAILED for orderId={} ({}): {}",
                    trade.fill.getOrderId(), reason, result.getDetail());
            notifier.send(String.format(
                    "⚠ %s: exit order FAILED (%s) — %s. The position is still open and still being managed; "
                            + "close it yourself if this keeps failing.",
                    trade.fill.getDisplaySymbol(), reason, result.getDetail()));
            return false;
        }

        trade.engine.forceExit(trade.context, exitPrice);
        stopFeed(trade);
        return true;
    }

    /**
     * Drops a finished trade, once its outcome has been reported.
     * <p>
     * Everything the trade held -- engine, milestone tracker, cost record,
     * feed -- goes with it. Previously closed trades stayed in the map for
     * the life of the process, so every /status, button build and /exit all
     * walked the whole session's history.
     * <p>
     * The order id is deliberately kept, in handledOrderIds, so a replayed
     * fill cannot re-open a trade that has already been settled.
     */
    private void discard(ActiveTrade trade) {
        activeTrades.remove(trade.fill.getOrderId());
        log.debug("Dropped finished trade orderId={}; {} still open",
                trade.fill.getOrderId(), activeTrades.size());
    }

    /**
     * Final P&L with charges recomputed at the real exit price, replacing
     * the entry-time estimate.
     * <p>
     * This is where the estimate's one weakness is corrected: sell-side
     * charges scale with the exit value, so quoting them at entry
     * understates by roughly Rs 19 on a +21% exit and Rs 89 on a +100% one.
     * Small against the profit itself, but there is no reason for the
     * closing number to carry it.
     */
    private String settlement(ActiveTrade trade, double exitPrice) {

        TradeFillEvent fill = trade.fill;
        TradeCost settled = chargesService.settledCost(
                fill.getInstrumentKey(), fill.getAveragePrice(), exitPrice, fill.getFilledQuantity());

        double gross = (exitPrice - fill.getAveragePrice()) * fill.getFilledQuantity();
        double net = settled.netProfitAt(fill.getAveragePrice(), exitPrice, fill.getFilledQuantity());
        double estimatedCharges = trade.cost.getTotalCharges();

        return String.format(
                "settled: gross %+.2f, charges %.2f (estimated %.2f at entry), net %+.2f",
                gross, settled.getTotalCharges(), estimatedCharges, net);
    }

    /** Costs above this share of deployed capital are worth calling out. */
    private static final double COSTLY_TRADE_FRACTION = 0.02;

    /**
     * Everything here is explicitly an estimate: the sell side is priced at
     * the entry price because the exit price is not yet known. The settled
     * figure arrives when the trade closes.
     * <p>
     * The warnings that follow all bite hardest on cheap expiry-day
     * premiums, where the tick is a large share of the price and flat
     * brokerage dominates -- exactly when the numbers are least intuitive.
     */
    private String buildTrackingMessage(TradeFillEvent fill, TradeCost cost, double breakeven, MilestoneLadder ladder) {

        StringBuilder message = new StringBuilder(String.format(
                "%s: now tracking (entry=%.2f, qty=%d, orderId=%s)%n"
                        + "estimated breakeven %.2f — %s costs %.2f on %.2f invested (%.3f%%)",
                fill.getInstrumentKey(), fill.getAveragePrice(), fill.getFilledQuantity(), fill.getOrderId(),
                breakeven, cost.isEstimated() ? "modelled" : "broker-quoted at entry price",
                cost.getTotalCharges(), cost.getInvestedAmount(), cost.getFractionOfInvested() * 100));

        String comparison = compareWithModel(fill, cost);
        if (comparison != null) {
            message.append(System.lineSeparator()).append(comparison);
        }

        if (cost.getFractionOfInvested() > COSTLY_TRADE_FRACTION) {
            message.append(String.format("%n⚠ costs are %.2f%% of deployed capital — this trade needs a large move just to break even",
                    cost.getFractionOfInvested() * 100));
        }

        double tick = fill.getTickSize();
        if (tick > 0) {
            double tickPercent = tick / fill.getAveragePrice() * 100;
            double firstRung = ladder.allThresholdsPercent()[0];
            if (tickPercent >= firstRung) {
                message.append(String.format(
                        "%n⚠ one tick (%.2f) is %.1f%% of this premium, coarser than the first ladder rung (%.1f%%) — early milestones will fire together",
                        tick, tickPercent, firstRung));
            }
        }

        return message.toString();
    }

    /**
     * Runs the built-in model alongside the broker's quote so the model can
     * be checked against reality trade by trade. Skipped when the primary
     * figure is already modelled, since it would only be compared to itself.
     */
    private String compareWithModel(TradeFillEvent fill, TradeCost actual) {

        if (actual.isEstimated()) {
            return null;
        }

        TradeCost modelled = referenceCharges.roundTripCost(
                fill.getInstrumentKey(), fill.getAveragePrice(), fill.getFilledQuantity());

        return String.format("model says %.2f (%+.2f vs broker)",
                modelled.getTotalCharges(), modelled.getTotalCharges() - actual.getTotalCharges());
    }

    /**
     * Reports net profit -- percentage above the cost-inclusive breakeven,
     * and the rupees that would actually be kept on exiting here. The
     * rupee figure is what makes it unambiguous; a percentage alone still
     * invites reading it as gross.
     */
    static String formatMilestoneMessage(TradeFillEvent fill, double currentPrice, double thresholdPercent,
                                         ActiveTrade trade) {
        double netProfit = (currentPrice - trade.context.getBasePrice()) * fill.getFilledQuantity();
        return String.format(
                "%s up %s%% net (entry=%.2f, breakeven=%.2f, current=%.2f) — %.0f after costs",
                fill.getInstrumentKey(), trimPercent(thresholdPercent),
                fill.getAveragePrice(), trade.context.getBasePrice(), currentPrice, netProfit);
    }

    private static String trimPercent(double percent) {
        return percent == Math.rint(percent) ? String.valueOf((long) percent) : String.valueOf(percent);
    }

    private static final class ActiveTrade {
        final TradeFillEvent fill;
        final TradeContext context;
        final ExitEngine engine;
        final ProfitMilestoneTracker tracker;
        final TradeCost cost;
        final MilestoneLadder ladder;
        volatile double lastPrice;
        /** Wall-clock time lastPrice was last set, for the /health command's tick-recency check. */
        volatile long lastPriceAt;
        volatile boolean breakevenReported;
        volatile boolean stopBreachReported;
        volatile boolean feedFailureReported;
        volatile MonitorMode mode = MonitorMode.MANAGED;
        volatile MarketDataFeed feed;

        ActiveTrade(TradeFillEvent fill, TradeContext context, ExitEngine engine,
                    ProfitMilestoneTracker tracker, TradeCost cost, MilestoneLadder ladder) {
            this.fill = fill;
            this.context = context;
            this.engine = engine;
            this.tracker = tracker;
            this.cost = cost;
            this.ladder = ladder;
            this.lastPrice = fill.getAveragePrice();
            this.lastPriceAt = System.currentTimeMillis();
        }
    }
}
