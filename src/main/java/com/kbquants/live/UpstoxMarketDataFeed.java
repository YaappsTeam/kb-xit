package com.kbquants.live;

import com.kbquants.session.MarketDataFeed;
import com.kbquants.session.PriceListener;
import com.upstox.ApiClient;
import com.upstox.Configuration;
import com.upstox.feeder.MarketUpdateV3;
import com.upstox.feeder.MarketDataStreamerV3;
import com.upstox.feeder.constants.Mode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Live MarketDataFeed backed by Upstox's V3 WebSocket market data streamer.
 * <p>
 * Authenticated with an Analytics Token ({@link UpstoxDataCredentials}),
 * which is valid for a year and covers the streaming API without a static
 * IP -- so live prices need no daily login. The daily
 * {@link UpstoxAuthService} flow is only needed for order placement.
 * <p>
 * {@link #start(PriceListener)} is asynchronous: it opens the WebSocket
 * connection and returns immediately. Prices arrive on the streamer's
 * callback thread and are forwarded to the given PriceListener as they
 * arrive. Call {@link #stop()} to close the connection.
 */
@Slf4j
public class UpstoxMarketDataFeed implements MarketDataFeed {

    private final UpstoxDataCredentials credentials;
    private final Set<String> instrumentKeys;
    private MarketDataStreamerV3 streamer;

    public UpstoxMarketDataFeed(UpstoxDataCredentials credentials, Set<String> instrumentKeys) {
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.instrumentKeys = Objects.requireNonNull(instrumentKeys, "instrumentKeys must not be null");

        if (instrumentKeys.isEmpty()) {
            throw new IllegalArgumentException("instrumentKeys must not be empty");
        }
    }

    @Override
    public void start(PriceListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");

        ApiClient apiClient = new ApiClient(credentials.isSandbox());
        apiClient.setAccessToken(credentials.getAnalyticsToken());
        Configuration.setDefaultApiClient(apiClient);

        streamer = new MarketDataStreamerV3(apiClient, instrumentKeys, Mode.LTPC);
        streamer.setOnMarketUpdateListener(update -> dispatchUpdate(update, listener));
        streamer.setOnErrorListener(error -> log.error("Upstox market data stream error", error));
        streamer.setOnCloseListener((code, reason) -> log.warn("Upstox market data stream closed: {} {}", code, reason));
        streamer.autoReconnect(true);

        log.info("Connecting to Upstox market data stream for {} instrument(s)", instrumentKeys.size());
        streamer.connect();
    }

    public void stop() {
        if (streamer != null) {
            streamer.disconnect();
        }
    }

    static void dispatchUpdate(MarketUpdateV3 update, PriceListener listener) {
        Map<String, MarketUpdateV3.Feed> feeds = update.getFeeds();
        if (feeds == null) return;

        for (MarketUpdateV3.Feed feed : feeds.values()) {
            MarketUpdateV3.LTPC ltpc = feed.getLtpc();
            if (ltpc == null) continue;

            long timestamp = ltpc.getLtt() > 0 ? ltpc.getLtt() : update.getCurrentTs();
            listener.onPrice(ltpc.getLtp(), timestamp);
        }
    }
}
