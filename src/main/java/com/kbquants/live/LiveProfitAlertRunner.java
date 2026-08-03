package com.kbquants.live;

import com.kbquants.notification.Notifier;
import com.kbquants.notification.ProfitMilestoneTracker;
import com.kbquants.session.MarketDataFeed;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * MVP live-monitoring orchestrator.
 * <p>
 * On each confirmed BUY fill (from UpstoxOrderFillFeed), starts watching
 * that instrument's live price (via a MarketDataFeed) and sends a
 * notification each time profit-from-entry ratchets through the next
 * milestone (ProfitMilestoneTracker). Places no orders itself -- entries
 * are assumed to happen elsewhere, and exits are a manual/future step
 * (limit or GTT orders at these same milestones is the planned next step).
 */
@Slf4j
public class LiveProfitAlertRunner {

    private final UpstoxCredentials credentials;
    private final Notifier notifier;
    private final Function<String, MarketDataFeed> feedFactory;
    private final Map<String, ProfitMilestoneTracker> trackersByOrderId = new ConcurrentHashMap<>();

    public LiveProfitAlertRunner(UpstoxCredentials credentials, Notifier notifier) {
        this(credentials, notifier, instrumentKey -> new UpstoxMarketDataFeed(credentials, Set.of(instrumentKey)));
    }

    LiveProfitAlertRunner(UpstoxCredentials credentials, Notifier notifier, Function<String, MarketDataFeed> feedFactory) {
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
        this.feedFactory = Objects.requireNonNull(feedFactory, "feedFactory must not be null");
    }

    public void start() {
        new UpstoxOrderFillFeed(credentials).start(this::onFill);
    }

    void onFill(TradeFillEvent fill) {

        ProfitMilestoneTracker tracker = new ProfitMilestoneTracker(fill.getAveragePrice());
        if (trackersByOrderId.putIfAbsent(fill.getOrderId(), tracker) != null) {
            log.debug("Ignoring duplicate fill event for orderId={}", fill.getOrderId());
            return;
        }

        log.info("Tracking new fill for profit alerts: orderId={} instrument={} entryPrice={}",
                fill.getOrderId(), fill.getInstrumentKey(), fill.getAveragePrice());

        feedFactory.apply(fill.getInstrumentKey())
                .start((price, timestamp) -> onPrice(fill, price));
    }

    void onPrice(TradeFillEvent fill, double currentPrice) {

        ProfitMilestoneTracker tracker = trackersByOrderId.get(fill.getOrderId());
        if (tracker == null) return;

        OptionalDouble crossed = tracker.checkAndAdvance(currentPrice);
        if (crossed.isPresent()) {
            notifier.send(formatMessage(fill, currentPrice, crossed.getAsDouble()));
        }
    }

    static String formatMessage(TradeFillEvent fill, double currentPrice, double thresholdPercent) {
        return String.format(
                "%s up %.1f%% from entry (entry=%.2f, current=%.2f)",
                fill.getInstrumentKey(), thresholdPercent, fill.getAveragePrice(), currentPrice);
    }
}
