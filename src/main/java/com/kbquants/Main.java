package com.kbquants;

import com.kbquants.domain.ActiveLadder;
import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.MilestoneSets;
import com.kbquants.domain.RiskSettings;
import com.kbquants.domain.TraderAccount;
import com.kbquants.domain.TradingToken;
import com.kbquants.instrument.InstrumentAwareTrackRequestResolver;
import com.kbquants.instrument.InstrumentCatalog;
import com.kbquants.instrument.InstrumentMasterLoader;
import com.kbquants.instrument.PositionSizer;
import com.kbquants.live.EndOfDaySchedule;
import com.kbquants.live.TradeMonitor;
import com.kbquants.live.UpstoxChargesService;
import com.kbquants.live.UpstoxDataCredentials;
import com.kbquants.live.UpstoxExitOrderPlacer;
import com.kbquants.live.UpstoxMarketDataFeed;
import com.kbquants.live.UpstoxOrderFillFeed;
import com.kbquants.live.UpstoxPositionQuery;
import com.kbquants.live.UpstoxQuoteService;
import com.kbquants.notification.TelegramCommandHandler;
import com.kbquants.notification.TelegramCredentials;
import com.kbquants.notification.TelegramNotifier;
import com.kbquants.session.AccountRegistry;
import com.kbquants.session.ChargesService;
import com.kbquants.session.EstimatedChargesService;
import com.kbquants.session.ExitOrderPlacer;
import com.kbquants.session.JsonTradeStore;
import com.kbquants.session.LiteralTrackRequestResolver;
import com.kbquants.session.MarketDataFeed;
import com.kbquants.session.NoOpOrderFillFeed;
import com.kbquants.session.OrderFillFeed;
import com.kbquants.session.PaperExitOrderPlacer;
import com.kbquants.session.PositionQuery;
import com.kbquants.session.SimulatedMarketDataFeed;
import com.kbquants.session.TradeFillEvent;
import com.kbquants.session.TradeStore;
import com.kbquants.session.TrackRequestResolver;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Entry point for paper trading mode: a trade is invoked via Telegram's
 * /track command, watched via a price feed, and managed by the full
 * ExitEngine + unified milestone ladder via TradeMonitor. No real broker
 * order is ever placed -- see PRODUCT_REQUIREMENTS.md F7.
 * <p>
 * One process manages every account in {@link AccountRegistry}: each gets
 * its own {@link TradeMonitor}, its own Upstox credentials, its own
 * Telegram chat, its own trade-state file, and its own daily order token --
 * see PRODUCT_REQUIREMENTS.md section F8 and IMPLEMENTATION_PLAN.md Phase
 * 3.5. What IS shared across every account: the JVM process, the Telegram
 * bot token (routing to the right account happens per-chat-id in
 * {@link TelegramCommandHandler}), and the instrument master cache.
 * <p>
 * Two switches, deliberately kept orthogonal and process-wide (every
 * account runs the same way, at least until a reason to split them
 * per-account shows up):
 * <ul>
 *   <li>{@code TRADING_MODE} -- who executes the trade. Only "paper" is
 *       wired up; live order placement is Phase 3 (IMPLEMENTATION_PLAN.md)
 *       and is the half that needs the daily OAuth token and a static IP.</li>
 *   <li>{@code MARKET_DATA} -- where prices come from. "simulated" (default)
 *       random-walks from the entry price; "live" streams real ticks from
 *       Upstox over WebSocket, each account authenticated with its own
 *       year-long, read-only Analytics Token.</li>
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

        String botToken = requireEnv("TELEGRAM_BOT_TOKEN");

        AccountRegistry registry = AccountRegistry.load(accountsFilePath());
        log.info("Loaded {} account(s) from the registry", registry.size());

        // These flags apply to every account identically, so they are
        // logged once here rather than once per account in
        // buildTradeMonitor -- the same message N times would look like a
        // per-account decision when it is a single process-wide one.
        boolean watchBrokerFills = Boolean.parseBoolean(
                System.getenv().getOrDefault("WATCH_BROKER_FILLS", "false"));
        if (watchBrokerFills) {
            log.info("WATCH_BROKER_FILLS is on — positions opened at any account's broker will be offered for "
                    + "adoption in that account's chat. Nothing is managed until accepted. Needs a /token first.");
        }

        boolean placeRealOrders = Boolean.parseBoolean(
                System.getenv().getOrDefault("PLACE_REAL_ORDERS", "false"));
        if (placeRealOrders) {
            log.warn("PLACE_REAL_ORDERS is ON — exits will place REAL SELL orders for every account. "
                    + "Each needs its own /token daily and a registered static IP.");
        } else {
            log.info("PLACE_REAL_ORDERS is off — exits are recorded and reported, no broker order is placed.");
        }

        // One download, shared by every account -- the instrument master is
        // public market data, not a per-account credential, so there is
        // nothing to isolate here and no reason to fetch it N times.
        InstrumentCatalog sharedCatalog = liveData
                ? InstrumentCatalog.loadFrom(new InstrumentMasterLoader())
                : null;

        // Built eagerly, all N accounts, before anything starts listening --
        // a bad credential or a missing field for account #7 must fail the
        // whole process at startup, not surface as a silent gap once
        // trading is already underway for accounts #1-6.
        Map<String, TradeMonitor> monitorsByAccountId = new LinkedHashMap<>();
        Map<String, TradeMonitor> monitorsByChatId = new LinkedHashMap<>();
        for (TraderAccount account : registry.all()) {
            TradeMonitor monitor = buildTradeMonitor(
                    account, liveData, sharedCatalog, botToken, watchBrokerFills, placeRealOrders);
            monitorsByAccountId.put(account.getAccountId(), monitor);
            monitorsByChatId.put(account.getTelegramChatId(), monitor);
        }

        EndOfDaySchedule endOfDay = endOfDaySchedule(monitorsByAccountId);
        if (endOfDay != null) {
            endOfDay.start();
        }

        // One bot token, one poller; which account a command belongs to is
        // resolved per-update from the sending chat id -- see
        // TelegramCommandHandler.
        TelegramCommandHandler commandHandler = new TelegramCommandHandler(botToken,
                chatId -> Optional.ofNullable(monitorsByChatId.get(chatId)));
        commandHandler.start();

        log.info("xit-mc started in PAPER trading mode with {} market data, managing {} account(s): {}. "
                        + "Send /track <symbol> to Telegram to begin.",
                liveData ? "LIVE Upstox" : "simulated", monitorsByAccountId.size(), monitorsByAccountId.keySet());

        Runtime.getRuntime().addShutdownHook(new Thread(commandHandler::stop));
    }

    /**
     * Wires one account's full stack: notifier, feed, resolver, charges,
     * persistence, order token, exit placer, fill feed, position query, and
     * the TradeMonitor that ties them together. Nothing here is shared with
     * any other account's stack -- see PRODUCT_REQUIREMENTS.md F8.
     */
    private static TradeMonitor buildTradeMonitor(TraderAccount account, boolean liveData,
                                                    InstrumentCatalog sharedCatalog, String botToken,
                                                    boolean watchBrokerFills, boolean placeRealOrders)
            throws IOException {

        TelegramNotifier notifier = new TelegramNotifier(
                new TelegramCredentials(botToken, account.getTelegramChatId()));

        MilestoneLadder defaultLadder = MilestoneSets.byName(account.getDefaultMilestoneSetName())
                .orElseThrow(() -> new IllegalStateException(
                        "account '" + account.getAccountId() + "' names unknown milestone set: "
                                + account.getDefaultMilestoneSetName()));
        ActiveLadder activeLadder = new ActiveLadder(defaultLadder);
        RiskSettings riskSettings = new RiskSettings(account.getMaxRiskPerTrade());

        Function<TradeFillEvent, MarketDataFeed> feedFactory;
        TrackRequestResolver trackRequestResolver;
        ChargesService chargesService;

        if (liveData) {
            UpstoxDataCredentials dataCredentials =
                    new UpstoxDataCredentials(account.getUpstoxAnalyticsToken(), account.isUpstoxSandbox());
            feedFactory = liveFeedFactory(dataCredentials);
            trackRequestResolver = instrumentAwareResolver(account, dataCredentials, sharedCatalog, activeLadder, riskSettings);
            chargesService = new UpstoxChargesService(dataCredentials);
        } else {
            feedFactory = Main::simulatedFeed;
            trackRequestResolver = new LiteralTrackRequestResolver();
            chargesService = new EstimatedChargesService();
        }

        // Open trades are written to disk and restored on the next start,
        // one file per account so a restart cannot cross-restore one
        // trader's positions under another's identity.
        TradeStore tradeStore = new JsonTradeStore(tradeStateFilePath(account));

        // Supplied at runtime via /token, because the daily Upstox login is
        // interactive and cannot be automated -- each account supplies its
        // own, in its own chat.
        TradingToken tradingToken = new TradingToken(ZoneId.of("Asia/Kolkata"));

        ExitOrderPlacer exitOrderPlacer = exitOrderPlacer(tradingToken, placeRealOrders);

        // The broker's order stream carries fills for the whole account, so
        // it is only ever paired with explicit adoption -- the two are set
        // together here so neither can be enabled without the other.
        // Process-wide switch: every account either watches its own fill
        // feed or none does.
        OrderFillFeed orderFillFeed = watchBrokerFills
                ? new UpstoxOrderFillFeed(tradingToken)
                : new NoOpOrderFillFeed();

        // Only ever asked once this account's /token arrives; without one
        // it reports itself unavailable rather than answering "nothing is
        // open", which would flag every managed trade as gone.
        PositionQuery positionQuery = new UpstoxPositionQuery(tradingToken);

        return new TradeMonitor(orderFillFeed, feedFactory, notifier, trackRequestResolver, activeLadder,
                chargesService, riskSettings, tradeStore, exitOrderPlacer, tradingToken, watchBrokerFills,
                positionQuery);
    }

    /**
     * {@code ACCOUNTS_FILE} overrides; otherwise {@code accounts.properties}
     * in the working directory, matching {@code accounts.properties.example}.
     */
    private static Path accountsFilePath() {
        String override = System.getenv("ACCOUNTS_FILE");
        return (override == null || override.isBlank()) ? Paths.get("accounts.properties") : Paths.get(override);
    }

    /**
     * {@code TRADE_STATE_DIR} overrides the directory (not the file --
     * with N accounts, one fixed file for all of them would let one
     * account's restart clobber another's state); otherwise
     * {@code ~/.xit-mc/open-trades/}. One file per account inside it,
     * named by accountId.
     */
    private static Path tradeStateFilePath(TraderAccount account) {
        String override = System.getenv("TRADE_STATE_DIR");
        Path dir = (override == null || override.isBlank())
                ? Paths.get(System.getProperty("user.home"), ".xit-mc", "open-trades")
                : Paths.get(override);
        return dir.resolve(account.getAccountId() + ".json");
    }

    /**
     * One WebSocket subscription per trade, scoped to just that trade's
     * instrument, authenticated with this account's own Analytics Token.
     * Fine at the handful-of-open-trades scale this runs at; if open trades
     * ever outgrow Upstox's concurrent-connection allowance, this becomes
     * one shared streamer fanning out by instrument key.
     */
    private static Function<TradeFillEvent, MarketDataFeed> liveFeedFactory(UpstoxDataCredentials dataCredentials) {
        return fill -> new UpstoxMarketDataFeed(dataCredentials, Set.of(fill.getInstrumentKey()));
    }

    /**
     * Symbol lookup plus price/quantity defaulting, sized against this
     * account's own capital and risk settings. The instrument master itself
     * is shared -- see {@code sharedCatalog} in {@link #main}.
     */
    private static TrackRequestResolver instrumentAwareResolver(TraderAccount account,
                                                                  UpstoxDataCredentials dataCredentials,
                                                                  InstrumentCatalog sharedCatalog,
                                                                  ActiveLadder activeLadder,
                                                                  RiskSettings riskSettings) {
        return new InstrumentAwareTrackRequestResolver(
                sharedCatalog,
                new UpstoxQuoteService(dataCredentials),
                new PositionSizer(account.getCapitalPerTrade(), riskSettings, activeLadder));
    }

    /**
     * Off unless EOD_EXIT_TIME is set. Closing positions on a clock is a
     * decision with real consequences, so it is opted into rather than
     * assumed -- and a wrong or misread time would square off a position
     * hours early. Process-wide: every account closes out at the same
     * market-clock time, since that is what the market itself does.
     * <p>
     * The zone defaults to the market's, not the machine's: a VM on UTC
     * would otherwise fire five and a half hours late, well after the
     * broker had already squared the position off itself.
     */
    private static EndOfDaySchedule endOfDaySchedule(Map<String, TradeMonitor> monitorsByAccountId) {

        String configured = System.getenv("EOD_EXIT_TIME");
        if (configured == null || configured.isBlank()) {
            log.info("EOD_EXIT_TIME is not set — no end-of-day close; your broker will square off intraday "
                    + "positions on its own terms.");
            return null;
        }

        LocalTime cutoff = LocalTime.parse(configured.trim());
        ZoneId zone = ZoneId.of(System.getenv().getOrDefault("EOD_TIMEZONE", "Asia/Kolkata"));

        return new EndOfDaySchedule(cutoff, zone,
                () -> monitorsByAccountId.values().forEach(TradeMonitor::onEndOfDay));
    }

    /**
     * Real orders require an explicit opt-in, a usable daily token and a
     * registered static IP. Only the first is a code concern; the flag
     * exists so that nothing else -- MARKET_DATA=live in particular -- can
     * imply permission to sell. Process-wide: either the deployment places
     * real orders or it does not.
     */
    private static ExitOrderPlacer exitOrderPlacer(TradingToken tradingToken, boolean placeRealOrders) {

        if (!placeRealOrders) {
            return new PaperExitOrderPlacer();
        }

        String product = System.getenv().getOrDefault("ORDER_PRODUCT", "I");
        return new UpstoxExitOrderPlacer(tradingToken, product);
    }

    private static MarketDataFeed simulatedFeed(TradeFillEvent fill) {
        return new SimulatedMarketDataFeed(fill.getAveragePrice(), PAPER_VOLATILITY_PERCENT);
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }
}
