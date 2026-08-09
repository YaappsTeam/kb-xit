package com.kbquants;

import com.kbquants.instrument.InstrumentAwareTrackRequestResolver;
import com.kbquants.instrument.InstrumentCatalog;
import com.kbquants.instrument.InstrumentMasterLoader;
import com.kbquants.instrument.PositionSizer;
import com.kbquants.live.EndOfDaySchedule;
import com.kbquants.live.TradeMonitor;
import com.kbquants.live.UpstoxDataCredentials;
import com.kbquants.live.UpstoxMarketDataFeed;
import com.kbquants.domain.ActiveLadder;
import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.RiskSettings;
import com.kbquants.domain.TradingToken;
import com.kbquants.live.UpstoxChargesService;
import com.kbquants.live.UpstoxExitOrderPlacer;
import com.kbquants.live.UpstoxOrderFillFeed;
import com.kbquants.live.UpstoxQuoteService;
import com.kbquants.session.ChargesService;
import com.kbquants.session.EstimatedChargesService;
import com.kbquants.session.ExitOrderPlacer;
import com.kbquants.session.JsonTradeStore;
import com.kbquants.session.PaperExitOrderPlacer;
import com.kbquants.session.TradeStore;
import com.kbquants.notification.TelegramCommandHandler;
import com.kbquants.notification.TelegramCredentials;
import com.kbquants.notification.TelegramNotifier;
import com.kbquants.session.TrackRequestResolver;
import com.kbquants.session.LiteralTrackRequestResolver;
import com.kbquants.session.MarketDataFeed;
import com.kbquants.session.NoOpOrderFillFeed;
import com.kbquants.session.OrderFillFeed;
import com.kbquants.session.SimulatedMarketDataFeed;
import com.kbquants.session.TradeFillEvent;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.function.Function;

/**
 * Entry point for paper trading mode: a trade is invoked via Telegram's
 * /track command, watched via a price feed, and managed by the full
 * ExitEngine + unified milestone ladder via TradeMonitor. No real broker
 * order is ever placed -- see PRODUCT_REQUIREMENTS.md F7.
 * <p>
 * Two switches, deliberately kept orthogonal:
 * <ul>
 *   <li>{@code TRADING_MODE} -- who executes the trade. Only "paper" is
 *       wired up; live order placement is Phase 3 (IMPLEMENTATION_PLAN.md)
 *       and is the half that needs the daily OAuth token and a static IP.</li>
 *   <li>{@code MARKET_DATA} -- where prices come from. "simulated" (default)
 *       random-walks from the entry price; "live" streams real ticks from
 *       Upstox over WebSocket, authenticated with the year-long, read-only
 *       Analytics Token.</li>
 * </ul>
 * MARKET_DATA=live with TRADING_MODE=paper is the useful combination today:
 * the exit engine reacts to genuine market prices while nothing can place an
 * order, because the Analytics Token has no trading permission at all.
 */
@Slf4j
public class Main {

    private static final double PAPER_VOLATILITY_PERCENT = 0.3;

    public static void main(String[] args) throws IOException {

        String tradingMode = System.getenv().getOrDefault("TRADING_MODE", "paper");
        if (!"paper".equalsIgnoreCase(tradingMode)) {
            log.error("TRADING_MODE={} is not supported yet -- only 'paper' is wired up so far. See IMPLEMENTATION_PLAN.md Phase 3.",
                    tradingMode);
            System.exit(1);
            return;
        }

        String marketData = System.getenv().getOrDefault("MARKET_DATA", "simulated");
        if (!"simulated".equalsIgnoreCase(marketData) && !"live".equalsIgnoreCase(marketData)) {
            log.error("MARKET_DATA={} is not recognised -- expected 'simulated' or 'live'.", marketData);
            System.exit(1);
            return;
        }
        boolean liveData = "live".equalsIgnoreCase(marketData);

        TelegramCredentials credentials = TelegramCredentials.fromEnv();
        TelegramNotifier notifier = new TelegramNotifier(credentials);

        // Everything below is resolved eagerly so a missing Analytics Token,
        // an unreachable instrument master or a bad CAPITAL_PER_TRADE fails
        // at startup rather than on the first /track, mid-trading-session.
        Function<TradeFillEvent, MarketDataFeed> feedFactory;
        TrackRequestResolver trackRequestResolver;
        ChargesService chargesService;

        // Shared between the monitor and position sizing so that switching
        // milestone sets moves the hard stop for both at once.
        ActiveLadder activeLadder = new ActiveLadder(MilestoneLadder.defaultLadder());

        // Seeded from the environment, then adjustable at runtime via /risk.
        RiskSettings riskSettings = new RiskSettings(maxRiskFromEnv());

        if (liveData) {
            UpstoxDataCredentials dataCredentials = UpstoxDataCredentials.fromEnv();
            feedFactory = liveFeedFactory(dataCredentials);
            trackRequestResolver = instrumentAwareResolver(dataCredentials, activeLadder, riskSettings);
            chargesService = new UpstoxChargesService(dataCredentials);
        } else {
            feedFactory = Main::simulatedFeed;
            trackRequestResolver = new LiteralTrackRequestResolver();
            chargesService = new EstimatedChargesService();
        }

        // Open trades are written to disk and restored on the next start.
        // The position does not disappear when the process does, so losing
        // the entry, breakeven, phase and ratcheted stop would leave it
        // open at the broker with nothing watching it.
        TradeStore tradeStore = new JsonTradeStore();

        // Supplied at runtime via /token, because the daily Upstox login is
        // interactive and cannot be automated.
        TradingToken tradingToken = new TradingToken(ZoneId.of("Asia/Kolkata"));

        // The only object in the system that can sell anything. Off unless
        // PLACE_REAL_ORDERS is explicitly enabled -- the difference between
        // this and the paper placer is real money, so it is never the
        // default and never implied by another setting.
        ExitOrderPlacer exitOrderPlacer = exitOrderPlacer(tradingToken);

        // The broker's order stream carries fills for the whole account, so
        // it is only ever paired with explicit adoption -- the two are set
        // together here so neither can be enabled without the other.
        boolean watchBrokerFills = Boolean.parseBoolean(
                System.getenv().getOrDefault("WATCH_BROKER_FILLS", "false"));

        OrderFillFeed orderFillFeed = watchBrokerFills
                ? new UpstoxOrderFillFeed(tradingToken)
                : new NoOpOrderFillFeed();

        if (watchBrokerFills) {
            log.info("WATCH_BROKER_FILLS is on — positions opened at your broker will be offered for adoption. "
                    + "Nothing is managed until you accept it. Needs a /token before the stream can start.");
        }

        TradeMonitor tradeMonitor = new TradeMonitor(orderFillFeed, feedFactory, notifier,
                trackRequestResolver, activeLadder, chargesService, riskSettings, tradeStore, exitOrderPlacer,
                tradingToken, watchBrokerFills);

        EndOfDaySchedule endOfDay = endOfDaySchedule(tradeMonitor);
        if (endOfDay != null) {
            endOfDay.start();
        }

        TelegramCommandHandler commandHandler = new TelegramCommandHandler(credentials, tradeMonitor);
        commandHandler.start();

        log.info("xit-mc started in PAPER trading mode with {} market data. Send /track <symbol> to Telegram to begin.",
                liveData ? "LIVE Upstox" : "simulated");

        Runtime.getRuntime().addShutdownHook(new Thread(commandHandler::stop));
    }

    /**
     * One WebSocket subscription per trade, scoped to just that trade's
     * instrument. Fine at the handful-of-open-trades scale this runs at; if
     * open trades ever outgrow Upstox's concurrent-connection allowance,
     * this becomes one shared streamer fanning out by instrument key.
     */
    private static Function<TradeFillEvent, MarketDataFeed> liveFeedFactory(UpstoxDataCredentials dataCredentials) {
        return fill -> new UpstoxMarketDataFeed(dataCredentials, Set.of(fill.getInstrumentKey()));
    }

    /**
     * Symbol lookup plus price/quantity defaulting. Requires the instrument
     * master (a ~2 MB download, refreshed weekly on Wednesdays or on demand
     * via /refresh) and CAPITAL_PER_TRADE, which is what a bare
     * {@code /track <symbol>} sizes against.
     */
    private static TrackRequestResolver instrumentAwareResolver(UpstoxDataCredentials dataCredentials,
                                                              ActiveLadder activeLadder,
                                                              RiskSettings riskSettings) throws IOException {

        String capital = System.getenv("CAPITAL_PER_TRADE");
        if (capital == null || capital.isBlank()) {
            throw new IllegalStateException(
                    "Missing required environment variable: CAPITAL_PER_TRADE (used to size a bare /track <symbol>)");
        }

        return new InstrumentAwareTrackRequestResolver(
                InstrumentCatalog.loadFrom(new InstrumentMasterLoader()),
                new UpstoxQuoteService(dataCredentials),
                new PositionSizer(Double.parseDouble(capital), riskSettings, activeLadder));
    }

    /**
     * Optional second ceiling. Where both apply the smaller wins, so capital
     * caps exposure and risk caps the loss -- a wide stop on a small
     * position rather than a narrow stop that ordinary noise would trip.
     */
    private static double maxRiskFromEnv() {

        String maxRisk = System.getenv("MAX_RISK_PER_TRADE");
        double value = maxRisk == null || maxRisk.isBlank() ? 0 : Double.parseDouble(maxRisk);

        if (value <= 0) {
            log.warn("MAX_RISK_PER_TRADE is not set — position size is capped by capital alone, so a hard-stop "
                    + "loss is CAPITAL_PER_TRADE x the set's hard stop ({}% on OPTIONS). Set one with /risk.",
                    (int) (MilestoneLadder.optionsLadder().getHardStopPercent() * 100));
        }
        return value;
    }

    /**
     * Off unless EOD_EXIT_TIME is set. Closing positions on a clock is a
     * decision with real consequences, so it is opted into rather than
     * assumed -- and a wrong or misread time would square off a position
     * hours early.
     * <p>
     * The zone defaults to the market's, not the machine's: a VM on UTC
     * would otherwise fire five and a half hours late, well after the
     * broker had already squared the position off itself.
     */
    private static EndOfDaySchedule endOfDaySchedule(TradeMonitor tradeMonitor) {

        String configured = System.getenv("EOD_EXIT_TIME");
        if (configured == null || configured.isBlank()) {
            log.info("EOD_EXIT_TIME is not set — no end-of-day close; your broker will square off intraday "
                    + "positions on its own terms.");
            return null;
        }

        LocalTime cutoff = LocalTime.parse(configured.trim());
        ZoneId zone = ZoneId.of(System.getenv().getOrDefault("EOD_TIMEZONE", "Asia/Kolkata"));

        return new EndOfDaySchedule(cutoff, zone, tradeMonitor::onEndOfDay);
    }

    /**
     * Real orders require an explicit opt-in, a usable daily token and a
     * registered static IP. Only the first is a code concern; the flag
     * exists so that nothing else -- MARKET_DATA=live in particular -- can
     * imply permission to sell.
     */
    private static ExitOrderPlacer exitOrderPlacer(TradingToken tradingToken) {

        if (!Boolean.parseBoolean(System.getenv().getOrDefault("PLACE_REAL_ORDERS", "false"))) {
            log.info("PLACE_REAL_ORDERS is off — exits are recorded and reported, no broker order is placed.");
            return new PaperExitOrderPlacer();
        }

        String product = System.getenv().getOrDefault("ORDER_PRODUCT", "I");
        log.warn("PLACE_REAL_ORDERS is ON — exits will place REAL SELL orders (product={}). "
                + "This needs a /token each day and a registered static IP.", product);

        return new UpstoxExitOrderPlacer(tradingToken, product);
    }

    private static MarketDataFeed simulatedFeed(TradeFillEvent fill) {
        return new SimulatedMarketDataFeed(fill.getAveragePrice(), PAPER_VOLATILITY_PERCENT);
    }
}
