package com.kbquants.notification;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/bot" + credentials.getBotToken() + "/sendMessage"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(buildFormBody(message)))
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
