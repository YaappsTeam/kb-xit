package com.kbquants.notification;

import lombok.Getter;

import java.util.Objects;
import java.util.function.Function;

/**
 * Credentials for sending alerts via a Telegram bot.
 * <p>
 * See https://core.telegram.org/bots/tutorial for how to create a bot via
 * BotFather (which issues the bot token) and how to obtain a chat id.
 */
@Getter
public final class TelegramCredentials {

    private final String botToken;
    private final String chatId;

    public TelegramCredentials(String botToken, String chatId) {
        this.botToken = Objects.requireNonNull(botToken, "botToken must not be null");
        this.chatId = Objects.requireNonNull(chatId, "chatId must not be null");
    }

    /**
     * Reads TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID from the process environment.
     */
    public static TelegramCredentials fromEnv() {
        return fromEnv(System::getenv);
    }

    static TelegramCredentials fromEnv(Function<String, String> env) {
        return new TelegramCredentials(
                requireEnv(env, "TELEGRAM_BOT_TOKEN"),
                requireEnv(env, "TELEGRAM_CHAT_ID"));
    }

    private static String requireEnv(Function<String, String> env, String name) {
        String value = env.apply(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }
}
