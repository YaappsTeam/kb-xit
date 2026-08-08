package com.kbquants.notification;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

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

    /**
     * The inline keyboard is hand-built JSON, so it is parsed back to prove
     * it is well-formed -- Telegram rejects the whole sendMessage with a
     * 400 if reply_markup does not parse.
     */
    @Test
    void shouldBuildParseableInlineKeyboardJson() {

        LinkedHashMap<String, String> choices = new LinkedHashMap<>();
        choices.put("EQUITY — 10 rungs", "ladder:EQUITY");
        choices.put("OPTIONS — 9 rungs", "ladder:OPTIONS");

        JsonObject parsed = new Gson().fromJson(TelegramNotifier.buildInlineKeyboard(choices), JsonObject.class);
        JsonArray rows = parsed.getAsJsonArray("inline_keyboard");

        assertEquals(2, rows.size());
        assertEquals("EQUITY — 10 rungs",
                rows.get(0).getAsJsonArray().get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("ladder:OPTIONS",
                rows.get(1).getAsJsonArray().get(0).getAsJsonObject().get("callback_data").getAsString());
    }

    @Test
    void shouldEscapeQuotesAndBackslashesInButtonLabels() {

        LinkedHashMap<String, String> choices = new LinkedHashMap<>();
        choices.put("a \"quoted\" back\\slash", "ladder:X");

        JsonObject parsed = new Gson().fromJson(TelegramNotifier.buildInlineKeyboard(choices), JsonObject.class);

        assertEquals("a \"quoted\" back\\slash",
                parsed.getAsJsonArray("inline_keyboard").get(0).getAsJsonArray()
                        .get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void shouldPutEachChoiceOnItsOwnRow() {

        LinkedHashMap<String, String> choices = new LinkedHashMap<>();
        choices.put("one", "ladder:A");
        choices.put("two", "ladder:B");
        choices.put("three", "ladder:C");

        JsonArray rows = new Gson().fromJson(TelegramNotifier.buildInlineKeyboard(choices), JsonObject.class)
                .getAsJsonArray("inline_keyboard");

        assertEquals(3, rows.size());
        rows.forEach(row -> assertEquals(1, row.getAsJsonArray().size()));
    }
}
