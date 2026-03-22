package com.kbquants.marketdata.parser;


import com.kbquants.marketdata.model.Candle;
import com.kbquants.marketdata.model.Timeframe;

import java.util.List;

public interface CandleParser<T> {

    List<Candle> parse(
            T brokerResponse,
            String symbol,
            Timeframe timeframe
    );
}