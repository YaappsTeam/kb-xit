package com.kbquants.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers only the pure request-body construction. The actual network call
 * (send) is not exercised here -- see DEVELOPMENT.md for why this
 * environment cannot verify real Telegram connectivity.
 */
class TelegramNotifierTest {

    @Test
    void shouldBuildFormEncodedBodyWithChatIdAndText() {

        TelegramCredentials credentials = new TelegramCredentials("bot-token-123", "chat-456");
        TelegramNotifier notifier = new TelegramNotifier(credentials);

        String body = notifier.buildFormBody("hello world");

        assertEquals("chat_id=chat-456&text=hello+world", body);
    }

    @Test
    void shouldUrlEncodeSpecialCharactersInMessage() {

        TelegramCredentials credentials = new TelegramCredentials("bot-token-123", "chat-456");
        TelegramNotifier notifier = new TelegramNotifier(credentials);

        String body = notifier.buildFormBody("NSE_EQ|INE848E01016 up 0.5% (entry=100.00)");

        assertEquals(
                "chat_id=chat-456&text=NSE_EQ%7CINE848E01016+up+0.5%25+%28entry%3D100.00%29",
                body);
    }
}
