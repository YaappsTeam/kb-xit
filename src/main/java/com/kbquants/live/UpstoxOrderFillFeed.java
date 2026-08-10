package com.kbquants.live;

import com.kbquants.domain.TradingToken;
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

    private final TradingToken tradingToken;
    private volatile PortfolioDataStreamer streamer;

    /**
     * Takes the runtime {@link TradingToken} rather than configured
     * credentials: the token arrives via /token during the session and
     * expires daily, so it cannot be read once at startup.
     */
    public UpstoxOrderFillFeed(TradingToken tradingToken) {
        this.tradingToken = Objects.requireNonNull(tradingToken, "tradingToken must not be null");
    }

    private volatile TradeFillListener listener;

    /**
     * Records the listener and connects only if a token is already held.
     * <p>
     * At startup there is usually no token -- it arrives by /token during
     * the session -- so failing here would mean the whole app could not
     * start with broker watching enabled. It waits instead, and connects
     * from {@link #onCredentialAvailable()}.
     */
    @Override
    public void start(TradeFillListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener must not be null");

        if (!tradingToken.isUsable()) {
            log.info("No trading token yet — the order stream will connect once one is supplied via /token");
            return;
        }
        connect();
    }

    @Override
    public void onCredentialAvailable() {
        if (listener != null && !isRunning() && tradingToken.isUsable()) {
            connect();
        }
    }

    private void connect() {

        ApiClient apiClient = new ApiClient(false);
        apiClient.setAccessToken(tradingToken.value());
        Configuration.setDefaultApiClient(apiClient);

        streamer = new PortfolioDataStreamer(apiClient, true, false, false, false);
        TradeFillListener target = listener;
        streamer.setOnOrderUpdateListener(update -> toFillEvent(update).ifPresent(target::onFill));
        streamer.setOnErrorListener(error -> log.error("Upstox order stream error", error));
        streamer.setOnCloseListener((code, reason) -> log.warn("Upstox order stream closed: {} {}", code, reason));
        streamer.autoReconnect(true);

        log.info("Connecting to Upstox order update stream");
        streamer.connect();
    }

    @Override
    public void stop() {
        PortfolioDataStreamer current = streamer;
        streamer = null;
        if (current != null) {
            try {
                current.disconnect();
            } catch (Exception e) {
                log.warn("Error closing Upstox order stream: {}", e.getMessage());
            }
        }
    }

    public boolean isRunning() {
        return streamer != null;
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
