package com.kbquants.notification;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
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
    }

    private static final class TelegramMessage {
        String text;
    }
}
