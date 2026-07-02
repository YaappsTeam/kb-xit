package com.kbquants.marketdata.config;

import java.util.Objects;

/**
 * Externalized configuration for the market data engine.
 *
 * <p>Values that were previously hardcoded (broker base URL, access token) are
 * provided here so that runtime behaviour can be changed without code edits, per
 * the architecture rule that configuration MUST be externalised.
 */
public final class MarketDataConfig {

    private final String upstoxBaseUrl;
    private final String upstoxAccessToken;

    public MarketDataConfig(String upstoxBaseUrl, String upstoxAccessToken) {
        this.upstoxBaseUrl = Objects.requireNonNull(upstoxBaseUrl, "upstoxBaseUrl must not be null");
        this.upstoxAccessToken = Objects.requireNonNull(upstoxAccessToken, "upstoxAccessToken must not be null");
    }

    public String getUpstoxBaseUrl() {
        return upstoxBaseUrl;
    }

    public String getUpstoxAccessToken() {
        return upstoxAccessToken;
    }
}
