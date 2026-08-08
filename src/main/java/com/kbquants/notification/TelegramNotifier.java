package com.kbquants.notification;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Sends alert messages via the Telegram Bot API (sendMessage).
 * <p>
 * A failed send is logged, not thrown -- a notification failure should
 * never interrupt live price monitoring.
 */
@Slf4j
public class TelegramNotifier implements Notifier {

    private static final String API_BASE = "https://api.telegram.org";

    private final TelegramCredentials credentials;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public TelegramNotifier(TelegramCredentials credentials) {
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
    }

    @Override
    public void send(String message) {
        Objects.requireNonNull(message, "message must not be null");
        post(buildFormBody(message));
    }

    /**
     * Renders the choices as a Telegram inline keyboard -- buttons attached
     * to the message itself. Tapping one sends a callback_query carrying
     * the token, which TelegramCommandHandler picks up in the same
     * getUpdates loop as ordinary commands.
     */
    @Override
    public void sendChoices(String prompt, LinkedHashMap<String, String> choices) {
        Objects.requireNonNull(prompt, "prompt must not be null");
        Objects.requireNonNull(choices, "choices must not be null");

        if (choices.isEmpty()) {
            send(prompt);
            return;
        }

        post(buildFormBody(prompt) + "&reply_markup=" + encode(buildInlineKeyboard(choices)));
    }

    /**
     * One button per row: labels here are descriptive enough that side by
     * side they would truncate on a phone.
     */
    static String buildInlineKeyboard(LinkedHashMap<String, String> choices) {
        StringBuilder json = new StringBuilder("{\"inline_keyboard\":[");
        boolean first = true;
        for (Map.Entry<String, String> choice : choices.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("[{\"text\":\"").append(escapeJson(choice.getKey()))
                .append("\",\"callback_data\":\"").append(escapeJson(choice.getValue())).append("\"}]");
        }
        return json.append("]}").toString();
    }

    static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private void post(String formBody) {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/bot" + credentials.getBotToken() + "/sendMessage"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("Telegram sendMessage failed: status={} body={}", response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Telegram sendMessage interrupted", e);
        } catch (IOException e) {
            log.error("Telegram sendMessage failed", e);
        }
    }

    String buildFormBody(String message) {
        return "chat_id=" + encode(credentials.getChatId()) + "&text=" + encode(message);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
