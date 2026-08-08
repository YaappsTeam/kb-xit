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
        public void onTrack(List<String> args) {
            events.add("track:" + String.join("|", args));
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

        @Override
        public void onAdoptionPaused(boolean paused) {
            events.add(paused ? "paused" : "resumed");
        }

        @Override
        public void onMonitorModeRequested(String target, com.kbquants.domain.MonitorMode mode) {
            events.add("mode:" + mode + ":" + target);
        }

        @Override
        public void onUnknownCommand(String command) {
            events.add("unknown:" + command);
        }

        @Override
        public void onHelpRequested() {
            events.add("help");
        }
    }

    @Test
    void shouldForwardTrackArgumentsUninterpreted() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/track NSE_EQ|INE848E01016 1500.50 10", listener);

        assertEquals(List.of("track:NSE_EQ|INE848E01016|1500.50|10"), listener.events);
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

        TelegramCommandHandler.dispatch("/track NIFTY 50", listener);

        assertEquals(List.of("track:NIFTY|50"), listener.events);
    }

    @Test
    void shouldForwardBareSymbolWithNoPriceOrQuantity() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/track NIFTY50", listener);

        assertEquals(List.of("track:NIFTY50"), listener.events);
    }

    @Test
    void shouldIgnoreTrackCommandWithNoArguments() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/track", listener);

        assertTrue(listener.events.isEmpty());
    }

    /**
     * /buy no longer exists. It must be reported rather than ignored --
     * silence would leave the user believing a position was being watched
     * when nothing was dispatched.
     */
    @Test
    void removedBuyCommandShouldBeReportedAsUnknown() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/buy NIFTY50", listener);

        assertEquals(List.of("unknown:/buy"), listener.events);
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
    void shouldDispatchPauseAndResume() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/pause", listener);
        TelegramCommandHandler.dispatch("/resume", listener);

        assertEquals(List.of("paused", "resumed"), listener.events);
    }

    @Test
    void shouldDispatchMonitorModeCommands() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/release order-1", listener);
        TelegramCommandHandler.dispatch("/observe order-1", listener);
        TelegramCommandHandler.dispatch("/manage all", listener);

        assertEquals(List.of("mode:RELEASED:order-1", "mode:OBSERVED:order-1", "mode:MANAGED:all"),
                listener.events);
    }

    @Test
    void shouldIgnoreMonitorModeCommandWithoutATarget() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/release", listener);

        assertTrue(listener.events.isEmpty());
    }

    @Test
    void shouldDispatchHelp() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/help", listener);

        assertEquals(List.of("help"), listener.events);
    }

    /** Telegram sends /start when a user first opens the bot. */
    @Test
    void startShouldAlsoShowHelp() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/start", listener);

        assertEquals(List.of("help"), listener.events);
    }

    /**
     * Help that has drifted from the dispatch switch is worse than none,
     * since it is trusted mid-trade.
     */
    @Test
    void helpTextShouldMentionEveryAcceptedCommand() {

        for (String command : List.of("/track", "/exit", "/status", "/ladder", "/refresh",
                "/pause", "/resume", "/release", "/observe", "/manage")) {
            assertTrue(TelegramCommandHandler.HELP_TEXT.contains(command),
                    () -> command + " missing from help text");
        }
    }

    @Test
    void shouldReportUnrecognizedCommand() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/nosuchcommand", listener);

        assertEquals(List.of("unknown:/nosuchcommand"), listener.events);
    }

    /**
     * Only command-shaped input is answered, so the bot stays quiet in
     * ordinary conversation rather than replying to every message.
     */
    @Test
    void shouldStaySilentOnPlainChatMessages() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("hello", listener);
        TelegramCommandHandler.dispatch("nifty looking strong today", listener);

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

        TelegramCommandHandler.dispatch("  /track   NSE_EQ|INE848E01016   1500   10  ", listener);

        assertEquals(List.of("track:NSE_EQ|INE848E01016|1500|10"), listener.events);
    }
}
