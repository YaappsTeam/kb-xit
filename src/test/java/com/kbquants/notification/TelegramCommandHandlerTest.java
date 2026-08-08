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
        public void onBuy(List<String> args) {
            events.add("buy:" + String.join("|", args));
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

        @Override
        public void onRefreshInstruments() {
            events.add("refresh");
        }

        @Override
        public void onLadderChoicesRequested() {
            events.add("ladderChoices");
        }

        @Override
        public void onLadderSelected(String setName) {
            events.add("ladderSelected:" + setName);
        }
    }

    @Test
    void shouldForwardBuyArgumentsUninterpreted() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/buy NSE_EQ|INE848E01016 1500.50 10", listener);

        assertEquals(List.of("buy:NSE_EQ|INE848E01016|1500.50|10"), listener.events);
    }

    /**
     * Splitting instrument from price/quantity is the resolver's job, not
     * the handler's: "NIFTY 50" is a two-token symbol while "ACC 25" is a
     * symbol plus a quantity, and only the instrument master can tell them
     * apart. The handler therefore forwards tokens verbatim.
     */
    @Test
    void shouldForwardMultiWordSymbolWithoutSplittingIt() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/buy NIFTY 50", listener);

        assertEquals(List.of("buy:NIFTY|50"), listener.events);
    }

    @Test
    void shouldForwardBareSymbolWithNoPriceOrQuantity() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/buy NIFTY50", listener);

        assertEquals(List.of("buy:NIFTY50"), listener.events);
    }

    @Test
    void shouldIgnoreBuyCommandWithNoArguments() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/buy", listener);

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
    void shouldDispatchRefreshCommand() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/refresh", listener);

        assertEquals(List.of("refresh"), listener.events);
    }

    @Test
    void bareLadderCommandShouldAskForTheChoicesToBePushed() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/ladder", listener);

        assertEquals(List.of("ladderChoices"), listener.events);
    }

    @Test
    void ladderCommandWithNameShouldSelectDirectly() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/ladder OPTIONS", listener);

        assertEquals(List.of("ladderSelected:OPTIONS"), listener.events);
    }

    /** A tapped inline-keyboard button arrives as a callback, not a message. */
    @Test
    void shouldDispatchLadderSelectionFromCallbackData() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatchCallback("ladder:OPTIONS", listener);

        assertEquals(List.of("ladderSelected:OPTIONS"), listener.events);
    }

    @Test
    void shouldIgnoreUnrecognizedCallbackData() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatchCallback("something:else", listener);
        TelegramCommandHandler.dispatchCallback(null, listener);
        TelegramCommandHandler.dispatchCallback("", listener);

        assertTrue(listener.events.isEmpty());
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

        assertEquals(List.of("buy:NSE_EQ|INE848E01016|1500|10"), listener.events);
    }
}
