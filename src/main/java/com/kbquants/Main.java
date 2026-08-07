package com.kbquants;

import com.kbquants.live.TradeMonitor;
import com.kbquants.live.UpstoxDataCredentials;
import com.kbquants.live.UpstoxMarketDataFeed;
import com.kbquants.notification.TelegramCommandHandler;
import com.kbquants.notification.TelegramCredentials;
import com.kbquants.notification.TelegramNotifier;
import com.kbquants.session.MarketDataFeed;
import com.kbquants.session.NoOpOrderFillFeed;
import com.kbquants.session.SimulatedMarketDataFeed;
import com.kbquants.session.TradeFillEvent;
import lombok.extern.slf4j.Slf4j;

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

    public static void main(String[] args) {

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

        // Resolved eagerly so a missing/blank Analytics Token fails at
        // startup rather than on the first /buy, mid-trading-session.
        Function<TradeFillEvent, MarketDataFeed> feedFactory =
                liveData ? liveFeedFactory() : Main::simulatedFeed;

        TradeMonitor tradeMonitor = new TradeMonitor(new NoOpOrderFillFeed(), feedFactory, notifier);

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
    private static Function<TradeFillEvent, MarketDataFeed> liveFeedFactory() {
        UpstoxDataCredentials dataCredentials = UpstoxDataCredentials.fromEnv();
        return fill -> new UpstoxMarketDataFeed(dataCredentials, Set.of(fill.getInstrumentKey()));
    }

    private static MarketDataFeed simulatedFeed(TradeFillEvent fill) {
        return new SimulatedMarketDataFeed(fill.getAveragePrice(), PAPER_VOLATILITY_PERCENT);
    }
}
