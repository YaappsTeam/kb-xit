package com.kbquants;

import com.kbquants.instrument.InstrumentAwareBuyRequestResolver;
import com.kbquants.instrument.InstrumentCatalog;
import com.kbquants.instrument.InstrumentMasterLoader;
import com.kbquants.instrument.PositionSizer;
import com.kbquants.live.TradeMonitor;
import com.kbquants.live.UpstoxDataCredentials;
import com.kbquants.live.UpstoxMarketDataFeed;
import com.kbquants.domain.ActiveLadder;
import com.kbquants.domain.MilestoneLadder;
import com.kbquants.live.UpstoxChargesService;
import com.kbquants.live.UpstoxQuoteService;
import com.kbquants.session.ChargesService;
import com.kbquants.session.EstimatedChargesService;
import com.kbquants.notification.TelegramCommandHandler;
import com.kbquants.notification.TelegramCredentials;
import com.kbquants.notification.TelegramNotifier;
import com.kbquants.session.BuyRequestResolver;
import com.kbquants.session.LiteralBuyRequestResolver;
import com.kbquants.session.MarketDataFeed;
import com.kbquants.session.NoOpOrderFillFeed;
import com.kbquants.session.SimulatedMarketDataFeed;
import com.kbquants.session.TradeFillEvent;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Set;
import java.util.function.Function;

/**
 * Entry point for paper trading mode: a trade is invoked via Telegram's
 * /buy command, watched via a price feed, and managed by the full
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
        // at startup rather than on the first /buy, mid-trading-session.
        Function<TradeFillEvent, MarketDataFeed> feedFactory;
        BuyRequestResolver buyRequestResolver;
        ChargesService chargesService;

        // Shared between the monitor and position sizing so that switching
        // milestone sets moves the hard stop for both at once.
        ActiveLadder activeLadder = new ActiveLadder(MilestoneLadder.defaultLadder());

        if (liveData) {
            UpstoxDataCredentials dataCredentials = UpstoxDataCredentials.fromEnv();
            feedFactory = liveFeedFactory(dataCredentials);
            buyRequestResolver = instrumentAwareResolver(dataCredentials, activeLadder);
            chargesService = new UpstoxChargesService(dataCredentials);
        } else {
            feedFactory = Main::simulatedFeed;
            buyRequestResolver = new LiteralBuyRequestResolver();
            chargesService = new EstimatedChargesService();
        }

        TradeMonitor tradeMonitor = new TradeMonitor(new NoOpOrderFillFeed(), feedFactory, notifier,
                buyRequestResolver, activeLadder, chargesService);

        TelegramCommandHandler commandHandler = new TelegramCommandHandler(credentials, tradeMonitor);
        commandHandler.start();

        log.info("xit-mc started in PAPER trading mode with {} market data. Send /buy <instrument> <price> <qty> to Telegram to begin.",
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
     * {@code /buy <symbol>} sizes against.
     */
    private static BuyRequestResolver instrumentAwareResolver(UpstoxDataCredentials dataCredentials,
                                                              ActiveLadder activeLadder) throws IOException {

        String capital = System.getenv("CAPITAL_PER_TRADE");
        if (capital == null || capital.isBlank()) {
            throw new IllegalStateException(
                    "Missing required environment variable: CAPITAL_PER_TRADE (used to size a bare /buy <symbol>)");
        }

        // Optional second ceiling. Where both apply the smaller wins, so
        // capital caps exposure and risk caps the loss -- a wide stop on a
        // small position rather than a narrow stop that noise would trip.
        String maxRisk = System.getenv("MAX_RISK_PER_TRADE");
        double maxRiskPerTrade = maxRisk == null || maxRisk.isBlank() ? 0 : Double.parseDouble(maxRisk);
        if (maxRiskPerTrade <= 0) {
            log.warn("MAX_RISK_PER_TRADE is not set — position size is capped by capital alone, so a hard-stop "
                    + "loss is CAPITAL_PER_TRADE x the set's hard stop ({}% on OPTIONS).",
                    (int) (MilestoneLadder.optionsLadder().getHardStopPercent() * 100));
        }

        return new InstrumentAwareBuyRequestResolver(
                InstrumentCatalog.loadFrom(new InstrumentMasterLoader()),
                new UpstoxQuoteService(dataCredentials),
                new PositionSizer(Double.parseDouble(capital), maxRiskPerTrade, activeLadder));
    }

    private static MarketDataFeed simulatedFeed(TradeFillEvent fill) {
        return new SimulatedMarketDataFeed(fill.getAveragePrice(), PAPER_VOLATILITY_PERCENT);
    }
}
