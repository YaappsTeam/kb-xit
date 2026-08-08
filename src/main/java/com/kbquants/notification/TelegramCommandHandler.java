package com.kbquants.notification;

import com.google.gson.Gson;
import com.kbquants.domain.MonitorMode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Long-polls Telegram's getUpdates Bot API, parses each incoming message as
 * a command (/buy, /exit, /status), and dispatches it to a
 * TelegramCommandListener. This is the inbound half of the Telegram control
 * plane -- TelegramNotifier is the outbound half.
 * <p>
 * The HTTP polling loop is not unit-tested (requires live network access to
 * api.telegram.org -- see DEVELOPMENT.md). The pure command-parsing logic
 * (dispatch/parse) is fully unit-tested against hand-built message text.
 */
@Slf4j
public class TelegramCommandHandler {

    private static final String API_BASE = "https://api.telegram.org";
    private static final int LONG_POLL_TIMEOUT_SECONDS = 30;

    /** Prefix on inline-keyboard callback tokens for milestone-set choices. */
    public static final String LADDER_CALLBACK_PREFIX = "ladder:";

    private final TelegramCredentials credentials;
    private final TelegramCommandListener listener;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    private volatile boolean running;
    private Thread pollingThread;
    private long lastUpdateId = 0;

    public TelegramCommandHandler(TelegramCredentials credentials, TelegramCommandListener listener) {
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
    }

    public void start() {
        running = true;
        // Deliberately NOT a daemon thread: this is the one thread whose
        // liveness defines whether the whole application is still supposed
        // to be running. If it were a daemon thread, the JVM would exit the
        // instant main() returns (no other non-daemon thread keeps it
        // alive), killing the process before it ever actually polls
        // Telegram. stop() below is what lets it shut down cleanly instead.
        pollingThread = new Thread(this::pollLoop, "telegram-command-poller");
        pollingThread.start();
        log.info("Telegram command handler started");
    }

    public void stop() {
        running = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
        log.info("Telegram command handler stopped");
    }

    private void pollLoop() {
        while (running) {
            try {
                pollOnce();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Telegram getUpdates poll failed", e);
            }
        }
    }

    private void pollOnce() throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/bot" + credentials.getBotToken()
                        + "/getUpdates?offset=" + (lastUpdateId + 1)
                        + "&timeout=" + LONG_POLL_TIMEOUT_SECONDS))
                .timeout(Duration.ofSeconds(LONG_POLL_TIMEOUT_SECONDS + 10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("Telegram getUpdates failed: status={} body={}", response.statusCode(), response.body());
            return;
        }

        TelegramUpdatesResponse parsed = gson.fromJson(response.body(), TelegramUpdatesResponse.class);
        if (parsed == null || parsed.result == null) {
            return;
        }

        for (TelegramUpdate update : parsed.result) {
            lastUpdateId = Math.max(lastUpdateId, update.update_id);

            if (update.message != null) {
                dispatch(update.message.text, listener);
            }

            if (update.callback_query != null) {
                // Acknowledge first: until answerCallbackQuery is called the
                // user's client keeps showing a spinner on the button, even
                // though the action itself has already been handled.
                acknowledgeCallback(update.callback_query.id);
                dispatchCallback(update.callback_query.data, listener);
            }
        }
    }

    private void acknowledgeCallback(String callbackQueryId) {
        if (callbackQueryId == null) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/bot" + credentials.getBotToken()
                            + "/answerCallbackQuery?callback_query_id="
                            + URLEncoder.encode(callbackQueryId, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Cosmetic only -- the command has still been dispatched.
            log.warn("Telegram answerCallbackQuery failed: {}", e.getMessage());
        }
    }

    /**
     * Parses a raw Telegram message text into a command and dispatches it
     * to the listener. Unrecognized or malformed commands are logged and
     * ignored -- a bad command must never crash the polling loop.
     */
    static void dispatch(String text, TelegramCommandListener listener) {
        if (text == null || text.isBlank()) {
            return;
        }

        String[] parts = text.trim().split("\\s+");

        switch (parts[0]) {
            case "/buy" -> dispatchBuy(parts, text, listener);
            case "/exit" -> dispatchExit(parts, text, listener);
            case "/status" -> listener.onStatusRequested();
            case "/refresh" -> listener.onRefreshInstruments();
            case "/ladder" -> dispatchLadder(parts, listener);
            case "/pause" -> listener.onAdoptionPaused(true);
            case "/resume" -> listener.onAdoptionPaused(false);
            case "/release" -> dispatchMode(parts, rawText(text), MonitorMode.RELEASED, listener);
            case "/observe" -> dispatchMode(parts, rawText(text), MonitorMode.OBSERVED, listener);
            case "/manage" -> dispatchMode(parts, rawText(text), MonitorMode.MANAGED, listener);
            default -> log.debug("Ignoring unrecognized command: {}", text);
        }
    }

    /**
     * Arguments are passed through untouched rather than split into
     * instrument/price/quantity here. Both halves of that split are
     * genuinely ambiguous without the instrument master: keys and symbols
     * can contain spaces ("NSE_INDEX|Nifty 50", "NIFTY 50"), and a trailing
     * number may be a quantity or part of the symbol itself.
     */
    private static void dispatchBuy(String[] parts, String rawText, TelegramCommandListener listener) {
        if (parts.length < 2) {
            log.warn("Malformed /buy command (expected /buy <symbol> [price] [qty]): {}", rawText);
            return;
        }
        listener.onBuy(List.of(Arrays.copyOfRange(parts, 1, parts.length)));
    }

    /**
     * Bare "/ladder" asks for the choices to be pushed back as buttons;
     * "/ladder OPTIONS" selects directly, for when you already know the
     * name and don't want the round trip.
     */
    private static void dispatchLadder(String[] parts, TelegramCommandListener listener) {
        if (parts.length == 1) {
            listener.onLadderChoicesRequested();
        } else {
            listener.onLadderSelected(parts[1]);
        }
    }

    private static String rawText(String text) {
        return text;
    }

    /**
     * These never close a position -- they only change how far the engine
     * may act on it. That is the whole point: /exit sells, these hand the
     * trade back.
     */
    private static void dispatchMode(String[] parts, String rawText, MonitorMode mode,
                                     TelegramCommandListener listener) {
        if (parts.length != 2) {
            log.warn("Malformed {} command (expected {} <orderId> or {} all): {}",
                    parts[0], parts[0], parts[0], rawText);
            return;
        }
        listener.onMonitorModeRequested(parts[1], mode);
    }

    /**
     * A tapped inline-keyboard button arrives as a callback_query rather
     * than a message, carrying the token that was attached to the button.
     */
    static void dispatchCallback(String data, TelegramCommandListener listener) {
        if (data == null || data.isBlank()) {
            return;
        }
        if (data.startsWith(LADDER_CALLBACK_PREFIX)) {
            listener.onLadderSelected(data.substring(LADDER_CALLBACK_PREFIX.length()));
        } else {
            log.debug("Ignoring unrecognized callback data: {}", data);
        }
    }

    private static void dispatchExit(String[] parts, String rawText, TelegramCommandListener listener) {
        if (parts.length != 2) {
            log.warn("Malformed /exit command (expected /exit <orderId> or /exit all): {}", rawText);
            return;
        }
        if (parts[1].equalsIgnoreCase("all")) {
            listener.onExitAll();
        } else {
            listener.onExit(parts[1]);
        }
    }

    private static final class TelegramUpdatesResponse {
        boolean ok;
        List<TelegramUpdate> result;
    }

    private static final class TelegramUpdate {
        long update_id;
        TelegramMessage message;
        TelegramCallbackQuery callback_query;
    }

    private static final class TelegramMessage {
        String text;
    }

    private static final class TelegramCallbackQuery {
        String id;
        String data;
    }
}
