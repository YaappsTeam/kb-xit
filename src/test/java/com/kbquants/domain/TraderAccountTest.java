package com.kbquants.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for TraderAccount.
 */
class TraderAccountTest {

    @Test
    void shouldBuildFromValidFields() {

        TraderAccount account = new TraderAccount(
                "alice", "111222", "analytics-token", "key", "secret",
                "https://example.com/cb", true, 50_000, 5_000, "EQUITY");

        assertEquals("alice", account.getAccountId());
        assertEquals("111222", account.getTelegramChatId());
        assertEquals("analytics-token", account.getUpstoxAnalyticsToken());
        assertEquals("key", account.getUpstoxApiKey());
        assertEquals("secret", account.getUpstoxApiSecret());
        assertEquals("https://example.com/cb", account.getUpstoxRedirectUri());
        assertTrue(account.isUpstoxSandbox());
        assertEquals(50_000, account.getCapitalPerTrade());
        assertEquals(5_000, account.getMaxRiskPerTrade());
        assertEquals("EQUITY", account.getDefaultMilestoneSetName());
        assertTrue(account.hasMaxRiskPerTrade());
    }

    @Test
    void hasMaxRiskPerTradeShouldBeFalseWhenRiskIsZero() {

        TraderAccount account = new TraderAccount(
                "alice", "111222", "analytics-token", "key", "secret",
                "https://example.com/cb", false, 50_000, 0, "EQUITY");

        assertFalse(account.hasMaxRiskPerTrade());
    }

    @Test
    void shouldRejectBlankAccountId() {

        assertThrows(IllegalArgumentException.class, () -> new TraderAccount(
                "  ", "111222", "analytics-token", "key", "secret",
                "https://example.com/cb", false, 50_000, 5_000, "EQUITY"));
    }

    @Test
    void shouldRejectNullTelegramChatId() {

        assertThrows(NullPointerException.class, () -> new TraderAccount(
                "alice", null, "analytics-token", "key", "secret",
                "https://example.com/cb", false, 50_000, 5_000, "EQUITY"));
    }

    @Test
    void shouldRejectZeroCapitalPerTrade() {

        assertThrows(IllegalArgumentException.class, () -> new TraderAccount(
                "alice", "111222", "analytics-token", "key", "secret",
                "https://example.com/cb", false, 0, 5_000, "EQUITY"));
    }

    @Test
    void shouldRejectNegativeCapitalPerTrade() {

        assertThrows(IllegalArgumentException.class, () -> new TraderAccount(
                "alice", "111222", "analytics-token", "key", "secret",
                "https://example.com/cb", false, -1, 5_000, "EQUITY"));
    }

    @Test
    void shouldRejectNegativeMaxRiskPerTrade() {

        assertThrows(IllegalArgumentException.class, () -> new TraderAccount(
                "alice", "111222", "analytics-token", "key", "secret",
                "https://example.com/cb", false, 50_000, -1, "EQUITY"));
    }
}
