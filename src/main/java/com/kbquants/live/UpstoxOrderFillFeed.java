package com.kbquants.live;

import com.kbquants.session.OrderFillFeed;
import com.kbquants.session.TradeFillEvent;
import com.kbquants.session.TradeFillListener;
import com.upstox.ApiClient;
import com.upstox.Configuration;
import com.upstox.feeder.OrderUpdate;
import com.upstox.feeder.PortfolioDataStreamer;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.Optional;

@Slf4j
public class UpstoxOrderFillFeed implements OrderFillFeed {

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

    @Override
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

    @Override
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
