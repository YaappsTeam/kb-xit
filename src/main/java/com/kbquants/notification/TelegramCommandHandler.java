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
 * a command (/track, /exit, /status), and dispatches it to a
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

    /** Prefixes for per-trade buttons. The suffix is an orderId, or "all". */
    public static final String EXIT_CALLBACK_PREFIX = "exit:";
    public static final String RELEASE_CALLBACK_PREFIX = "release:";
    public static final String OBSERVE_CALLBACK_PREFIX = "observe:";
    public static final String MANAGE_CALLBACK_PREFIX = "manage:";

    /**
     * Telegram rejects callback_data beyond 64 bytes. Generated orderIds
     * ("telegram-" plus a UUID) leave comfortable room, but a broker id
     * could be longer, so buttons are skipped rather than silently
     * truncated -- a truncated token would target the wrong trade.
     */
    static final int MAX_CALLBACK_BYTES = 64;

    /**
     * Lives next to the dispatch switch below so the two are updated
     * together -- help that has drifted from what the bot actually accepts
     * is worse than none, since it is trusted mid-trade.
     */
    // Laid out with separators rather than aligned columns: Telegram
    // renders messages in a proportional font, so padded columns arrive
    // ragged.
    public static final String HELP_TEXT = String.join(System.lineSeparator(),
            "xit-mc — exit management. It never buys; it manages the exit of positions you already hold.",
            "",
            "TRACK A POSITION",
            "/track <symbol> — price = last traded, quantity sized from your capital and risk",
            "/track <symbol> <qty> — explicit quantity",
            "/track <symbol> <price> <qty> — fully explicit; the only form with simulated data",
            "Symbols ignore case and spaces, e.g. nifty25000ce18aug26",
            "",
            "CLOSE A POSITION",
            "/exit [orderId|all] — sell now; bare shows a button per open trade",
            "",
            "STEP BACK WITHOUT SELLING",
            "/observe [orderId|all] — keep the alerts, no automatic exit",
            "/release [orderId|all] — stop watching entirely; position stays open",
            "/manage [orderId|all] — hand it back to the engine",
            "Each of these shows buttons when sent without a target.",
            "/pause, /resume — stop or resume taking on new trades",
            "",
            "SETTINGS",
            "/status — open trades: entry, breakeven, phase, stop, mode",
            "/ladder [name] — choose the milestone set; bare shows buttons",
            "/risk [amount|off] — rupees a trade may lose at its stop; bare shows the current setting",
            "/refresh — re-fetch the instrument master now",
            "/help — this message",
            "",
            "Every percentage reported is net of brokerage and taxes.");

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
            case "/help", "/start" -> listener.onHelpRequested();
            case "/track" -> dispatchTrack(parts, text, listener);
            case "/exit" -> dispatchExit(parts, text, listener);
            case "/status" -> listener.onStatusRequested();
            case "/refresh" -> listener.onRefreshInstruments();
            case "/ladder" -> dispatchLadder(parts, listener);
            case "/risk" -> dispatchRisk(parts, text, listener);
            case "/pause" -> listener.onAdoptionPaused(true);
            case "/resume" -> listener.onAdoptionPaused(false);
            case "/release" -> dispatchMode(parts, rawText(text), MonitorMode.RELEASED, listener);
            case "/observe" -> dispatchMode(parts, rawText(text), MonitorMode.OBSERVED, listener);
            case "/manage" -> dispatchMode(parts, rawText(text), MonitorMode.MANAGED, listener);
            default -> dispatchUnknown(parts[0], listener);
        }
    }

    /**
     * Arguments are passed through untouched rather than split into
     * instrument/price/quantity here. Both halves of that split are
     * genuinely ambiguous without the instrument master: keys and symbols
     * can contain spaces ("NSE_INDEX|Nifty 50", "NIFTY 50"), and a trailing
     * number may be a quantity or part of the symbol itself.
     */
    private static void dispatchTrack(String[] parts, String rawText, TelegramCommandListener listener) {
        if (parts.length < 2) {
            reportMalformed(parts[0], "/track <symbol> [price] [qty]", rawText, listener);
            return;
        }
        listener.onTrack(List.of(Arrays.copyOfRange(parts, 1, parts.length)));
    }

    /**
     * Logged and answered. The log alone is not enough: it is on the
     * server, and the person who mistyped is looking at Telegram.
     */
    private static void reportMalformed(String command, String usage, String rawText,
                                        TelegramCommandListener listener) {
        log.warn("Malformed {} command (expected {}): {}", command, usage, rawText);
        listener.onMalformedCommand(command, usage);
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
     * Anything starting with "/" was meant as a command, so a
     * typo -- or a name that no longer exists -- gets answered rather than
     * silently dropped. Staying quiet is the dangerous option here: the
     * user would believe a position was being watched when nothing had
     * been dispatched.
     * <p>
     * Ordinary chat is left alone; only command-shaped input is answered,
     * so the bot does not respond to every message in the thread.
     */
    private static void dispatchUnknown(String command, TelegramCommandListener listener) {
        if (!command.startsWith("/")) {
            log.debug("Ignoring non-command message: {}", command);
            return;
        }
        log.warn("Unrecognized command: {}", command);
        listener.onUnknownCommand(command);
    }

    /**
     * These never close a position -- they only change how far the engine
     * may act on it. That is the whole point: /exit sells, these hand the
     * trade back.
     */
    private static void dispatchMode(String[] parts, String rawText, MonitorMode mode,
                                     TelegramCommandListener listener) {
        if (parts.length == 1) {
            listener.onMonitorModeChoicesRequested(mode);
            return;
        }
        if (parts.length != 2) {
            reportMalformed(parts[0], parts[0] + " <orderId> or " + parts[0] + " all", rawText, listener);
            return;
        }
        listener.onMonitorModeRequested(parts[1], mode);
    }

    /** The callback prefix a monitoring mode's buttons carry. */
    public static String callbackPrefixFor(MonitorMode mode) {
        return switch (mode) {
            case RELEASED -> RELEASE_CALLBACK_PREFIX;
            case OBSERVED -> OBSERVE_CALLBACK_PREFIX;
            case MANAGED -> MANAGE_CALLBACK_PREFIX;
        };
    }

    /** Whether a token fits Telegram's callback_data limit. */
    public static boolean fitsCallbackData(String token) {
        return token.getBytes(StandardCharsets.UTF_8).length <= MAX_CALLBACK_BYTES;
    }

    /**
     * Bare "/risk" reports the current ceiling; "/risk 5000" sets it and
     * "/risk off" removes it. Rejecting anything else rather than guessing:
     * a mistyped figure here silently changes how much every subsequent
     * trade can lose.
     */
    private static void dispatchRisk(String[] parts, String rawText, TelegramCommandListener listener) {

        if (parts.length == 1) {
            listener.onRiskShow();
            return;
        }
        if (parts.length != 2) {
            reportMalformed("/risk", "/risk <amount> or /risk off", rawText, listener);
            return;
        }
        if ("off".equalsIgnoreCase(parts[1])) {
            listener.onRiskSet(0);
            return;
        }
        try {
            double amount = Double.parseDouble(parts[1]);
            if (amount <= 0) {
                reportMalformed("/risk", "/risk <positive amount> or /risk off", rawText, listener);
                return;
            }
            listener.onRiskSet(amount);
        } catch (NumberFormatException e) {
            reportMalformed("/risk", "/risk <amount> or /risk off", rawText, listener);
        }
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
        } else if (data.startsWith(EXIT_CALLBACK_PREFIX)) {
            dispatchExitTarget(data.substring(EXIT_CALLBACK_PREFIX.length()), listener);
        } else if (data.startsWith(RELEASE_CALLBACK_PREFIX)) {
            listener.onMonitorModeRequested(data.substring(RELEASE_CALLBACK_PREFIX.length()), MonitorMode.RELEASED);
        } else if (data.startsWith(OBSERVE_CALLBACK_PREFIX)) {
            listener.onMonitorModeRequested(data.substring(OBSERVE_CALLBACK_PREFIX.length()), MonitorMode.OBSERVED);
        } else if (data.startsWith(MANAGE_CALLBACK_PREFIX)) {
            listener.onMonitorModeRequested(data.substring(MANAGE_CALLBACK_PREFIX.length()), MonitorMode.MANAGED);
        } else {
            log.debug("Ignoring unrecognized callback data: {}", data);
        }
    }

    private static void dispatchExit(String[] parts, String rawText, TelegramCommandListener listener) {
        if (parts.length == 1) {
            listener.onExitChoicesRequested();
            return;
        }
        if (parts.length != 2) {
            reportMalformed("/exit", "/exit <orderId> or /exit all", rawText, listener);
            return;
        }
        dispatchExitTarget(parts[1], listener);
    }

    private static void dispatchExitTarget(String target, TelegramCommandListener listener) {
        if (target.equalsIgnoreCase("all")) {
            listener.onExitAll();
        } else {
            listener.onExit(target);
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
