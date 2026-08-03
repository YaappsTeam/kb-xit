package com.kbquants.live;

import com.upstox.ApiClient;
import com.upstox.Configuration;
import com.upstox.feeder.OrderUpdate;
import com.upstox.feeder.PortfolioDataStreamer;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.Optional;

/**
 * Listens to Upstox's portfolio-stream-feed WebSocket (order updates only --
 * position/holding/GTT updates are not requested) and surfaces only
 * confirmed BUY fills as TradeFillEvents. This is the trigger for starting
 * live profit-milestone monitoring on a trade (see LiveProfitAlertRunner).
 * <p>
 * Order status ("complete") and transaction type ("BUY") string values are
 * matched case-insensitively, since exact casing could not be verified
 * against a live payload from this development environment -- see the
 * network-access caveat in DEVELOPMENT.md.
 */
@Slf4j
public class UpstoxOrderFillFeed {

    private static final String COMPLETE_STATUS = "complete";
    private static final String BUY_TRANSACTION_TYPE = "BUY";

    private final UpstoxCredentials credentials;
    private PortfolioDataStreamer streamer;

    public UpstoxOrderFillFeed(UpstoxCredentials credentials) {
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");

        if (!credentials.hasAccessToken()) {
            throw new IllegalStateException(
                    "UpstoxCredentials has no access token; complete the UpstoxAuthService login flow first");
        }
    }

    public void start(TradeFillListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");

        ApiClient apiClient = new ApiClient(credentials.isSandbox());
        apiClient.setAccessToken(credentials.getAccessToken());
        Configuration.setDefaultApiClient(apiClient);

        streamer = new PortfolioDataStreamer(apiClient, true, false, false, false);
        streamer.setOnOrderUpdateListener(update -> toFillEvent(update).ifPresent(listener::onFill));
        streamer.setOnErrorListener(error -> log.error("Upstox order stream error", error));
        streamer.setOnCloseListener((code, reason) -> log.warn("Upstox order stream closed: {} {}", code, reason));
        streamer.autoReconnect(true);

        log.info("Connecting to Upstox order update stream");
        streamer.connect();
    }

    public void stop() {
        if (streamer != null) {
            streamer.disconnect();
        }
    }

    static Optional<TradeFillEvent> toFillEvent(OrderUpdate update) {

        if (update.getStatus() == null || !update.getStatus().equalsIgnoreCase(COMPLETE_STATUS)) {
            return Optional.empty();
        }
        if (update.getTransactionType() == null || !update.getTransactionType().equalsIgnoreCase(BUY_TRANSACTION_TYPE)) {
            return Optional.empty();
        }

        return Optional.of(new TradeFillEvent(
                update.getOrderId(),
                update.getInstrumentKey(),
                update.getAveragePrice(),
                update.getFilledQuantity()));
    }
}
