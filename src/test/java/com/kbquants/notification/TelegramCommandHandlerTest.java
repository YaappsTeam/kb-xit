package com.kbquants.notification;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        @Override
        public void onMalformedCommand(String command, String usage) {
            events.add("malformed:" + command + ":" + usage);
        }

        @Override
        public void onRiskShow() {
            events.add("riskShow");
        }

        @Override
        public void onRiskSet(double maxRiskPerTrade) {
            events.add("riskSet:" + maxRiskPerTrade);
        }

        @Override
        public void onExitChoicesRequested() {
            events.add("exitChoices");
        }

        @Override
        public void onMonitorModeChoicesRequested(com.kbquants.domain.MonitorMode mode) {
            events.add("modeChoices:" + mode);
        }

        @Override
        public void onTokenStatusRequested() {
            events.add("tokenStatus");
        }

        @Override
        public void onTokenProvided(String token) {
            events.add("tokenProvided:" + token);
        }

        @Override
        public void onAdoptRequested(String orderId) {
            events.add("adopt:" + orderId);
        }

        @Override
        public void onIgnoreRequested(String orderId) {
            events.add("ignore:" + orderId);
        }

        @Override
        public void onPendingAdoptionsRequested() {
            events.add("pendingAdoptions");
        }

        @Override
        public void onReconcileRequested() {
            events.add("reconcile");
        }

        @Override
        public String accountId() {
            return "test-account";
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

    /**
     * A recognised command with no arguments must answer. Logging alone
     * puts the message on the server while the person who mistyped is
     * looking at Telegram, expecting a trade to be tracked.
     */
    @Test
    void shouldReportTrackCommandWithNoArguments() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/track", listener);

        assertEquals(List.of("malformed:/track:/track <symbol> [price] [qty]"), listener.events);
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

    /**
     * Bare /exit offers buttons rather than erroring: orderIds are UUIDs,
     * and retyping one while a position moves is the friction this removes.
     */
    @Test
    void bareExitShouldOfferButtons() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/exit", listener);

        assertEquals(List.of("exitChoices"), listener.events);
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
    void bareModeCommandsShouldOfferButtons() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/release", listener);
        TelegramCommandHandler.dispatch("/observe", listener);
        TelegramCommandHandler.dispatch("/manage", listener);

        assertEquals(List.of("modeChoices:RELEASED", "modeChoices:OBSERVED", "modeChoices:MANAGED"),
                listener.events);
    }

    @Test
    void tappedTradeButtonsShouldDispatchTheRightAction() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatchCallback("exit:order-1", listener);
        TelegramCommandHandler.dispatchCallback("exit:all", listener);
        TelegramCommandHandler.dispatchCallback("release:order-1", listener);
        TelegramCommandHandler.dispatchCallback("observe:order-2", listener);
        TelegramCommandHandler.dispatchCallback("manage:all", listener);

        assertEquals(List.of("exit:order-1", "exitAll", "mode:RELEASED:order-1",
                "mode:OBSERVED:order-2", "mode:MANAGED:all"), listener.events);
    }

    /**
     * Telegram rejects callback_data over 64 bytes, and a truncated token
     * would act on the wrong trade.
     */
    @Test
    void shouldRecogniseWhenATokenIsTooLongForCallbackData() {

        assertTrue(TelegramCommandHandler.fitsCallbackData("exit:telegram-" + java.util.UUID.randomUUID()));
        assertFalse(TelegramCommandHandler.fitsCallbackData("exit:" + "x".repeat(70)));
    }

    /**
     * Every command that can be malformed must answer -- silence is the
     * failure mode this exists to remove.
     */
    @Test
    void everyArgumentTakingCommandShouldAnswerWhenBare() {

        for (String command : List.of("/track", "/exit", "/release", "/observe", "/manage", "/risk")) {
            RecordingListener listener = new RecordingListener();
            TelegramCommandHandler.dispatch(command, listener);
            assertEquals(1, listener.events.size(), () -> command + " stayed silent");
        }
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
                "/pause", "/resume", "/release", "/observe", "/manage", "/risk", "/help", "/token", "/adopt", "/positions")) {
            assertTrue(TelegramCommandHandler.HELP_TEXT.contains(command),
                    () -> command + " missing from help text");
        }
    }

    @Test
    void bareRiskShouldReportTheCurrentSetting() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/risk", listener);

        assertEquals(List.of("riskShow"), listener.events);
    }

    @Test
    void riskWithAnAmountShouldSetIt() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/risk 5000", listener);

        assertEquals(List.of("riskSet:5000.0"), listener.events);
    }

    @Test
    void riskOffShouldRemoveTheCeiling() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/risk OFF", listener);

        assertEquals(List.of("riskSet:0.0"), listener.events);
    }

    /**
     * A mistyped figure here silently changes how much every subsequent
     * trade can lose, so it is rejected rather than guessed at.
     */
    @Test
    void riskShouldRejectNonNumericAndNonPositiveAmounts() {

        for (String bad : List.of("/risk abc", "/risk 0", "/risk -100", "/risk 1 2")) {
            RecordingListener listener = new RecordingListener();
            TelegramCommandHandler.dispatch(bad, listener);
            assertEquals(1, listener.events.size(), () -> bad + " gave " + listener.events);
            assertTrue(listener.events.get(0).startsWith("malformed:/risk"),
                    () -> bad + " gave " + listener.events.get(0));
        }
    }

    /**
     * /ladder previously had two lines where an optional argument covers
     * both, which read as a duplicate. /track is the deliberate exception:
     * its three lines are three genuinely different argument shapes, not
     * the same thing said twice.
     */
    @Test
    void helpTextShouldNotRepeatACommandThatNeedsOnlyOneLine() {

        for (String command : List.of("/ladder", "/exit", "/status", "/refresh",
                "/pause", "/resume", "/release", "/observe", "/manage", "/risk", "/help")) {
            // "/pause, /resume" legitimately share a line, so a trailing
            // comma counts as a mention just like a trailing space.
            long occurrences = TelegramCommandHandler.HELP_TEXT.lines()
                    .filter(line -> line.contains(command + " ") || line.contains(command + ","))
                    .count();
            assertEquals(1, occurrences, () -> command + " appears " + occurrences + " times in help");
        }
    }

    @Test
    void helpTextShouldShowEachTrackForm() {

        long trackForms = TelegramCommandHandler.HELP_TEXT.lines()
                .filter(line -> line.startsWith("/track "))
                .count();

        assertEquals(3, trackForms, "expected the bare, quantity and fully explicit forms");
    }

    @Test
    void bareTokenShouldReportStatus() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/token", listener);

        assertEquals(List.of("tokenStatus"), listener.events);
    }

    @Test
    void tokenWithAValueShouldSupplyIt() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/token abc123", listener);

        assertEquals(List.of("tokenProvided:abc123"), listener.events);
    }

    /**
     * A token pasted into chat stays in the history on both devices and on
     * Telegram's servers, so the message is deleted. Recognising which
     * messages carry a credential is the part worth testing.
     */
    @Test
    void shouldRecogniseMessagesCarryingACredential() {

        assertTrue(TelegramCommandHandler.carriesASecret("/token abc123"));
        assertTrue(TelegramCommandHandler.carriesASecret("  /TOKEN abc123  "));

        assertFalse(TelegramCommandHandler.carriesASecret("/token"));
        assertFalse(TelegramCommandHandler.carriesASecret("/status"));
        assertFalse(TelegramCommandHandler.carriesASecret(null));
    }

    /**
     * A broker's order stream carries the whole account, so a detected
     * position is offered rather than taken. These are the two answers.
     */
    @Test
    void shouldDispatchAdoptAndIgnoreFromButtons() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatchCallback("adopt:order-9", listener);
        TelegramCommandHandler.dispatchCallback("ignore:order-9", listener);

        assertEquals(List.of("adopt:order-9", "ignore:order-9"), listener.events);
    }

    @Test
    void bareAdoptShouldListWhatIsWaiting() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/adopt", listener);

        assertEquals(List.of("pendingAdoptions"), listener.events);
    }

    @Test
    void adoptWithAnIdShouldTakeThatPosition() {

        RecordingListener listener = new RecordingListener();

        TelegramCommandHandler.dispatch("/adopt order-9", listener);

        assertEquals(List.of("adopt:order-9"), listener.events);
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

    // ---- chat-id routing (Phase 3.5.3) ----

    @Test
    void chatIdOfShouldReadTheChatIdFromAMessage() {

        TelegramCommandHandler.TelegramMessage message = new TelegramCommandHandler.TelegramMessage();
        message.chat = new TelegramCommandHandler.TelegramChat();
        message.chat.id = 111222333L;

        assertEquals("111222333", TelegramCommandHandler.chatIdOf(message));
    }

    @Test
    void chatIdOfShouldReturnNullWhenChatIsMissing() {

        TelegramCommandHandler.TelegramMessage message = new TelegramCommandHandler.TelegramMessage();
        message.chat = null;

        assertNull(TelegramCommandHandler.chatIdOf(message));
    }

    @Test
    void constructorShouldRejectBlankBotToken() {

        assertThrows(IllegalArgumentException.class,
                () -> new TelegramCommandHandler("  ", chatId -> Optional.empty()));
    }

    @Test
    void constructorShouldRejectNullBotToken() {

        assertThrows(NullPointerException.class,
                () -> new TelegramCommandHandler(null, chatId -> Optional.empty()));
    }

    @Test
    void constructorShouldRejectNullListenerResolver() {

        assertThrows(NullPointerException.class,
                () -> new TelegramCommandHandler("token", null));
    }

    @Test
    void constructorShouldAcceptAValidBotTokenAndResolver() {

        TelegramCommandHandler handler = new TelegramCommandHandler("token", chatId -> Optional.empty());

        assertNotNull(handler);
    }

    // ---- MDC log correlation (story #23) ----

    @Test
    void withAccountLogContextShouldPutTheListenersAccountIdInMdcForTheDurationOfTheAction() {

        RecordingListener listener = new RecordingListener();
        java.util.concurrent.atomic.AtomicReference<String> seenDuring = new java.util.concurrent.atomic.AtomicReference<>();

        TelegramCommandHandler.withAccountLogContext(listener, () -> seenDuring.set(org.slf4j.MDC.get("accountId")));

        assertEquals("test-account", seenDuring.get());
        assertNull(org.slf4j.MDC.get("accountId"), "must not leak into whatever runs next on this thread");
    }

    @Test
    void withAccountLogContextShouldClearMdcEvenIfTheActionThrows() {

        RecordingListener listener = new RecordingListener();

        assertThrows(RuntimeException.class, () ->
                TelegramCommandHandler.withAccountLogContext(listener, () -> {
                    throw new RuntimeException("boom");
                }));

        assertNull(org.slf4j.MDC.get("accountId"));
    }
}
