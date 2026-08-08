package com.kbquants.live;

import com.kbquants.domain.ActiveLadder;
import com.kbquants.domain.ExitModel;
import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.MilestoneSets;
import com.kbquants.domain.MonitorMode;
import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.TradeContext;
import com.kbquants.engine.ExitEngine;
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
import com.kbquants.session.TradeFillEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
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
 * OrderFillFeed (a real broker, or Telegram's /buy command in paper mode --
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
     * never meant to touch. Until then only /buy invocations arrive, so
     * this is a safety valve waiting for Phase 3.
     */
    private volatile boolean adoptingNewTrades = true;

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

    /**
     * Takes the ActiveLadder rather than a ladder so that position sizing,
     * which needs the same hard stop, cannot drift out of step when the
     * user switches sets.
     */
    public TradeMonitor(OrderFillFeed orderFillFeed, Function<TradeFillEvent, MarketDataFeed> feedFactory,
                         Notifier notifier, TrackRequestResolver trackRequestResolver, ActiveLadder activeLadder,
                         ChargesService chargesService) {
        this.chargesService = Objects.requireNonNull(chargesService, "chargesService must not be null");
        this.feedFactory = Objects.requireNonNull(feedFactory, "feedFactory must not be null");
        this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
        this.trackRequestResolver = Objects.requireNonNull(trackRequestResolver, "trackRequestResolver must not be null");
        this.activeLadder = Objects.requireNonNull(activeLadder, "activeLadder must not be null");
        Objects.requireNonNull(orderFillFeed, "orderFillFeed must not be null").start(this::onFill);
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
                fill.getFilledQuantity(), ExitModel.MODERATE, OwnershipMode.MILESTONE);

        ActiveTrade trade = new ActiveTrade(fill, context, new ExitEngine(context, ladderForTrade),
                new ProfitMilestoneTracker(breakeven, ladderForTrade), cost);

        if (activeTrades.putIfAbsent(fill.getOrderId(), trade) != null) {
            log.debug("Ignoring duplicate fill event for orderId={}", fill.getOrderId());
            return;
        }

        log.info("Tracking new trade: orderId={} instrument={} entryPrice={} breakeven={} cost={}",
                fill.getOrderId(), fill.getInstrumentKey(), fill.getAveragePrice(), breakeven, cost);
        notifier.send(buildTrackingMessage(fill, cost, breakeven, ladderForTrade));

        feedFactory.apply(fill).start((price, timestamp) -> onPrice(trade, price));
    }

    void onPrice(ActiveTrade trade, double currentPrice) {

        if (trade.context.isClosed() || trade.mode == MonitorMode.RELEASED) {
            return;
        }

        trade.lastPrice = currentPrice;
        trade.engine.onPriceUpdate(currentPrice, trade.context);

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

            trade.engine.forceExit(trade.context, currentPrice);
            log.info("Stop-loss hit: orderId={} instrument={} price={} stopLoss={}",
                    trade.fill.getOrderId(), trade.fill.getInstrumentKey(), currentPrice, stopLoss);
            notifier.send(String.format("%s: stop-loss hit at %.2f (sl=%.2f) — trade closed%n%s",
                    trade.fill.getInstrumentKey(), currentPrice, stopLoss, settlement(trade, currentPrice)));
            return;
        }

        // Breakeven is its own event rather than a ladder rung: it is 0%
        // net profit by definition, and the ladder only carries positive
        // thresholds. It is the first thing worth telling the user --
        // from here on, anything gained is theirs.
        if (!trade.breakevenReported && currentPrice >= trade.context.getBasePrice()) {
            trade.breakevenReported = true;
            notifier.send(String.format("%s: breakeven — costs of %.2f covered at %.2f",
                    trade.fill.getInstrumentKey(), trade.cost.getTotalCharges(), currentPrice));
        }

        OptionalDouble crossed = trade.tracker.checkAndAdvance(currentPrice);
        if (crossed.isPresent()) {
            notifier.send(formatMilestoneMessage(trade.fill, currentPrice, crossed.getAsDouble(), trade));
        }
    }

    @Override
    public void onTrack(List<String> args) {

        TrackRequest request = trackRequestResolver.resolve(args);

        if (!request.isAccepted()) {
            log.info("Rejected /buy {}: {}", args, request.getRejectionReason());
            notifier.send("Cannot buy: " + request.getRejectionReason());
            return;
        }

        if (request.getNote() != null) {
            notifier.send(String.format("%s @ %.2f x %d — %s",
                    request.getDisplaySymbol(), request.getPrice(), request.getQuantity(), request.getNote()));
        }

        onFill(new TradeFillEvent("telegram-" + UUID.randomUUID(),
                request.getInstrumentKey(), request.getPrice(), request.getQuantity(), request.getTickSize()));
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
        notifier.send(command + " is not a command. Available: "
                + "/track /exit /status /ladder /refresh /pause /resume /release /observe /manage");
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
            trade.mode = mode;
            log.info("Monitor mode for orderId={} set to {}", trade.fill.getOrderId(), mode);
            notifier.send(describeModeChange(trade, mode));
        }
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

    private void forceExit(ActiveTrade trade) {
        double exitPrice = trade.lastPrice > 0 ? trade.lastPrice : trade.fill.getAveragePrice();
        trade.engine.forceExit(trade.context, exitPrice);
        log.info("Force exit: orderId={} instrument={} price={}",
                trade.fill.getOrderId(), trade.fill.getInstrumentKey(), exitPrice);
        notifier.send(String.format("%s: force-exited at %.2f (orderId=%s)%n%s",
                trade.fill.getInstrumentKey(), exitPrice, trade.fill.getOrderId(), settlement(trade, exitPrice)));
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
        volatile double lastPrice;
        volatile boolean breakevenReported;
        volatile boolean stopBreachReported;
        volatile MonitorMode mode = MonitorMode.MANAGED;

        ActiveTrade(TradeFillEvent fill, TradeContext context, ExitEngine engine,
                    ProfitMilestoneTracker tracker, TradeCost cost) {
            this.fill = fill;
            this.context = context;
            this.engine = engine;
            this.tracker = tracker;
            this.cost = cost;
            this.lastPrice = fill.getAveragePrice();
        }
    }
}
