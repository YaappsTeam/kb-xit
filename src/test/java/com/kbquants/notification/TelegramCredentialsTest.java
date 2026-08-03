package com.kbquants.notification;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelegramCredentialsTest {

    @Test
    void shouldBuildCredentialsFromEnvironmentLookup() {

        Map<String, String> env = Map.of(
                "TELEGRAM_BOT_TOKEN", "bot-token-123",
                "TELEGRAM_CHAT_ID", "chat-456"
        );

        TelegramCredentials credentials = TelegramCredentials.fromEnv(env::get);

        assertEquals("bot-token-123", credentials.getBotToken());
        assertEquals("chat-456", credentials.getChatId());
    }

    @Test
    void shouldThrowWhenBotTokenIsMissing() {

        Map<String, String> env = Map.of("TELEGRAM_CHAT_ID", "chat-456");

        assertThrows(IllegalStateException.class, () -> TelegramCredentials.fromEnv(env::get));
    }

    @Test
    void shouldThrowWhenChatIdIsMissing() {

        Map<String, String> env = Map.of("TELEGRAM_BOT_TOKEN", "bot-token-123");

        assertThrows(IllegalStateException.class, () -> TelegramCredentials.fromEnv(env::get));
    }
}
