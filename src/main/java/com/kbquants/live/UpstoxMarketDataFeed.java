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
 * Requires a valid (same-day) access token in the supplied UpstoxCredentials --
 * obtain one via {@link UpstoxAuthService}'s authorization-code flow first.
 * <p>
 * {@link #start(PriceListener)} is asynchronous: it opens the WebSocket
 * connection and returns immediately. Prices arrive on the streamer's
 * callback thread and are forwarded to the given PriceListener as they
 * arrive. Call {@link #stop()} to close the connection.
 */
@Slf4j
public class UpstoxMarketDataFeed implements MarketDataFeed {

    private final UpstoxCredentials credentials;
    private final Set<String> instrumentKeys;
    private MarketDataStreamerV3 streamer;

    public UpstoxMarketDataFeed(UpstoxCredentials credentials, Set<String> instrumentKeys) {
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.instrumentKeys = Objects.requireNonNull(instrumentKeys, "instrumentKeys must not be null");

        if (!credentials.hasAccessToken()) {
            throw new IllegalStateException(
                    "UpstoxCredentials has no access token; complete the UpstoxAuthService login flow first");
        }
        if (instrumentKeys.isEmpty()) {
            throw new IllegalArgumentException("instrumentKeys must not be empty");
        }
    }

    @Override
    public void start(PriceListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");

        ApiClient apiClient = new ApiClient(credentials.isSandbox());
        apiClient.setAccessToken(credentials.getAccessToken());
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
