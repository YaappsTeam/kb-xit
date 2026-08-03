package com.kbquants.session;


import com.kbquants.domain.TradeContext;
import com.kbquants.engine.ExitEngine;
import lombok.Getter;

public class TradingSession implements PriceListener {

    @Getter
    private final TradingMode tradingMode;
    @Getter
    private final TradeContext tradeContext;
    private final ExitEngine exitEngine;

    public TradingSession(TradingMode tradingMode, TradeContext tradeContext) {
        this.tradingMode = tradingMode;
        this.tradeContext = tradeContext;
        this.exitEngine = new ExitEngine(tradeContext);
    }

    @Override
    public void onPrice(double price, long timestamp) {

        if (tradeContext.isClosed()) {
            return;
        }

        exitEngine.onPriceUpdate(price, tradeContext);
    }
}
