package com.kbquants;

import com.kbquants.live.TradeMonitor;
import com.kbquants.notification.TelegramCommandHandler;
import com.kbquants.notification.TelegramCredentials;
import com.kbquants.notification.TelegramNotifier;
import com.kbquants.session.NoOpOrderFillFeed;
import com.kbquants.session.SimulatedMarketDataFeed;
import lombok.extern.slf4j.Slf4j;

/**
 * Entry point for paper trading mode: a trade is invoked via Telegram's
 * /buy command, watched with a simulated price feed, and managed by the
 * full ExitEngine + unified milestone ladder via TradeMonitor. No real
 * broker or real money is involved -- see PRODUCT_REQUIREMENTS.md F7.
 * <p>
 * Live mode (a real broker feeding fills and prices) is Phase 3 --
 * see IMPLEMENTATION_PLAN.md.
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

        TelegramCredentials credentials = TelegramCredentials.fromEnv();
        TelegramNotifier notifier = new TelegramNotifier(credentials);

        TradeMonitor tradeMonitor = new TradeMonitor(
                new NoOpOrderFillFeed(),
                fill -> new SimulatedMarketDataFeed(fill.getAveragePrice(), PAPER_VOLATILITY_PERCENT),
                notifier);

        TelegramCommandHandler commandHandler = new TelegramCommandHandler(credentials, tradeMonitor);
        commandHandler.start();

        log.info("xit-mc started in PAPER trading mode. Send /buy <instrument> <price> <qty> to Telegram to begin.");

        Runtime.getRuntime().addShutdownHook(new Thread(commandHandler::stop));
    }
}
