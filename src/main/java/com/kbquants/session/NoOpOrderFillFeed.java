package com.kbquants.session;

/**
 * A fill feed that never produces fills on its own. Used in paper trading
 * mode, where trades are invoked directly via Telegram's /track command
 * (see TelegramCommandListener) rather than detected from a broker feed.
 */
public class NoOpOrderFillFeed implements OrderFillFeed {

    @Override
    public void start(TradeFillListener listener) {
    }

    @Override
    public void stop() {
    }
}
