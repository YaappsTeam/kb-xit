package com.kbquants.notification;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests only the pure command-parsing/dispatch logic. The actual Telegram
 * getUpdates HTTP polling is not exercised here -- see DEVELOPMENT.md for
 * why this environment cannot verify real Telegram connectivity.
 */
class TelegramCommandHandlerTest {

    private static class RecordingListener implements TelegramCommandListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onBuy(String instrumentKey, double price, int quantity) {
            events.add("buy:" + instrumentKey + ":" + price + ":" + quantity);
        }

        @Override
        public void onExit(String orderId) {
            events.add("exit:" + orderId);
        }

        @Override
        public void onExitAll() {
            events.add("exitAll");
        }

        @Override
        public void onStatusRequested() {
            events.add("status");
        }
    }

    @Test
    void shouldDispatchValidBuyCommand() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/buy NSE_EQ|INE848E01016 1500.50 10", listener);

        assertEquals(List.of("buy:NSE_EQ|INE848E01016:1500.5:10"), listener.events);
    }

    @Test
    void shouldIgnoreMalformedBuyCommandWithWrongArgCount() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/buy NSE_EQ|INE848E01016 1500.50", listener);

        assertTrue(listener.events.isEmpty());
    }

    @Test
    void shouldIgnoreBuyCommandWithNonNumericPrice() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/buy NSE_EQ|INE848E01016 abc 10", listener);

        assertTrue(listener.events.isEmpty());
    }

    @Test
    void shouldDispatchExitCommandWithOrderId() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/exit order-123", listener);

        assertEquals(List.of("exit:order-123"), listener.events);
    }

    @Test
    void shouldDispatchExitAllCaseInsensitively() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/exit ALL", listener);

        assertEquals(List.of("exitAll"), listener.events);
    }

    @Test
    void shouldIgnoreMalformedExitCommand() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/exit", listener);

        assertTrue(listener.events.isEmpty());
    }

    @Test
    void shouldDispatchStatusCommand() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/status", listener);

        assertEquals(List.of("status"), listener.events);
    }

    @Test
    void shouldIgnoreUnrecognizedCommand() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/help", listener);

        assertTrue(listener.events.isEmpty());
    }

    @Test
    void shouldIgnoreNullOrBlankText() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch(null, listener);
        TelegramCommandHandler.dispatch("   ", listener);

        assertTrue(listener.events.isEmpty());
    }

    @Test
    void shouldTolerateExtraWhitespaceBetweenTokens() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("  /buy   NSE_EQ|INE848E01016   1500   10  ", listener);

        assertEquals(List.of("buy:NSE_EQ|INE848E01016:1500.0:10"), listener.events);
    }
}
