package com.kbquants.live;

import com.kbquants.domain.ExitModel;
import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.OwnershipMode;
import com.kbquants.domain.TradeContext;
import com.kbquants.engine.ExitEngine;
import com.kbquants.notification.Notifier;
import com.kbquants.notification.ProfitMilestoneTracker;
import com.kbquants.notification.TelegramCommandListener;
import com.kbquants.session.MarketDataFeed;
import com.kbquants.session.OrderFillFeed;
import com.kbquants.session.TradeFillEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;
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
 * basePrice is set equal to entryPrice for live/paper trades (breakeven
 * protection once Phase 2 is reached) -- this differs from the
 * simulation/backtest default of entry x 2.0, which is a research
 * parameter, not a live trading default. See IMPLEMENTATION_PLAN.md.
 */
@Slf4j
public class TradeMonitor implements TelegramCommandListener {

    private final Notifier notifier;
    private final Function<TradeFillEvent, MarketDataFeed> feedFactory;
    private final MilestoneLadder ladder;
    private final Map<String, ActiveTrade> activeTrades = new ConcurrentHashMap<>();

    public TradeMonitor(OrderFillFeed orderFillFeed, Function<TradeFillEvent, MarketDataFeed> feedFactory, Notifier notifier) {
        this(orderFillFeed, feedFactory, notifier, MilestoneLadder.defaultLadder());
    }

    public TradeMonitor(OrderFillFeed orderFillFeed, Function<TradeFillEvent, MarketDataFeed> feedFactory,
                         Notifier notifier, MilestoneLadder ladder) {
        this.feedFactory = Objects.requireNonNull(feedFactory, "feedFactory must not be null");
        this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
        this.ladder = Objects.requireNonNull(ladder, "ladder must not be null");
        Objects.requireNonNull(orderFillFeed, "orderFillFeed must not be null").start(this::onFill);
    }

    void onFill(TradeFillEvent fill) {

        TradeContext context = new TradeContext(
                fill.getOrderId(), fill.getAveragePrice(), fill.getAveragePrice(),
                fill.getFilledQuantity(), ExitModel.MODERATE, OwnershipMode.MILESTONE);

        ActiveTrade trade = new ActiveTrade(
                fill, context, new ExitEngine(context, ladder), new ProfitMilestoneTracker(fill.getAveragePrice(), ladder));

        if (activeTrades.putIfAbsent(fill.getOrderId(), trade) != null) {
            log.debug("Ignoring duplicate fill event for orderId={}", fill.getOrderId());
            return;
        }

        log.info("Tracking new trade: orderId={} instrument={} entryPrice={}",
                fill.getOrderId(), fill.getInstrumentKey(), fill.getAveragePrice());
        notifier.send(String.format("%s: now tracking (entry=%.2f, qty=%d, orderId=%s)",
                fill.getInstrumentKey(), fill.getAveragePrice(), fill.getFilledQuantity(), fill.getOrderId()));

        feedFactory.apply(fill).start((price, timestamp) -> onPrice(trade, price));
    }

    void onPrice(ActiveTrade trade, double currentPrice) {

        if (trade.context.isClosed()) {
            return;
        }

        trade.lastPrice = currentPrice;
        trade.engine.onPriceUpdate(currentPrice, trade.context);

        if (!trade.context.isClosed() && trade.context.getCurrentStopLoss() > 0
                && currentPrice <= trade.context.getCurrentStopLoss()) {
            double stopLoss = trade.context.getCurrentStopLoss();
            trade.engine.forceExit(trade.context, currentPrice);
            log.info("Stop-loss hit: orderId={} instrument={} price={} stopLoss={}",
                    trade.fill.getOrderId(), trade.fill.getInstrumentKey(), currentPrice, stopLoss);
            notifier.send(String.format("%s: stop-loss hit at %.2f (sl=%.2f) — trade closed",
                    trade.fill.getInstrumentKey(), currentPrice, stopLoss));
            return;
        }

        OptionalDouble crossed = trade.tracker.checkAndAdvance(currentPrice);
        if (crossed.isPresent()) {
            notifier.send(formatMilestoneMessage(trade.fill, currentPrice, crossed.getAsDouble()));
        }
    }

    @Override
    public void onBuy(String instrumentKey, double price, int quantity) {
        onFill(new TradeFillEvent("telegram-" + UUID.randomUUID(), instrumentKey, price, quantity));
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
    public void onStatusRequested() {

        StringBuilder sb = new StringBuilder();
        for (ActiveTrade trade : activeTrades.values()) {
            if (trade.context.isClosed()) continue;
            sb.append(String.format("%s: entry=%.2f current=%.2f phase=%s sl=%.2f orderId=%s%n",
                    trade.fill.getInstrumentKey(), trade.fill.getAveragePrice(), trade.lastPrice,
                    trade.context.getCurrentPhase(), trade.context.getCurrentStopLoss(), trade.fill.getOrderId()));
        }

        notifier.send(sb.length() == 0 ? "No active trades" : sb.toString());
    }

    private void forceExit(ActiveTrade trade) {
        double exitPrice = trade.lastPrice > 0 ? trade.lastPrice : trade.fill.getAveragePrice();
        trade.engine.forceExit(trade.context, exitPrice);
        log.info("Force exit: orderId={} instrument={} price={}",
                trade.fill.getOrderId(), trade.fill.getInstrumentKey(), exitPrice);
        notifier.send(String.format("%s: force-exited at %.2f (orderId=%s)",
                trade.fill.getInstrumentKey(), exitPrice, trade.fill.getOrderId()));
    }

    static String formatMilestoneMessage(TradeFillEvent fill, double currentPrice, double thresholdPercent) {
        return String.format(
                "%s up %.1f%% from entry (entry=%.2f, current=%.2f)",
                fill.getInstrumentKey(), thresholdPercent, fill.getAveragePrice(), currentPrice);
    }

    private static final class ActiveTrade {
        final TradeFillEvent fill;
        final TradeContext context;
        final ExitEngine engine;
        final ProfitMilestoneTracker tracker;
        volatile double lastPrice;

        ActiveTrade(TradeFillEvent fill, TradeContext context, ExitEngine engine, ProfitMilestoneTracker tracker) {
            this.fill = fill;
            this.context = context;
            this.engine = engine;
            this.tracker = tracker;
            this.lastPrice = fill.getAveragePrice();
        }
    }
}
