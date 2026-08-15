package com.kbquants.session;

import com.kbquants.domain.TraderAccount;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for AccountRegistry.
 */
class AccountRegistryTest {

    private static final String TWO_ACCOUNTS = """
            account.alice.telegramChatId=111
            account.alice.upstoxAnalyticsToken=alice-analytics
            account.alice.upstoxApiKey=alice-key
            account.alice.upstoxApiSecret=alice-secret
            account.alice.upstoxRedirectUri=https://example.com/cb
            account.alice.capitalPerTrade=50000
            account.alice.maxRiskPerTrade=5000
            account.alice.defaultMilestoneSetName=EQUITY

            account.bob.telegramChatId=222
            account.bob.upstoxAnalyticsToken=bob-analytics
            account.bob.upstoxApiKey=bob-key
            account.bob.upstoxApiSecret=bob-secret
            account.bob.upstoxRedirectUri=https://example.com/cb
            account.bob.capitalPerTrade=100000
            account.bob.defaultMilestoneSetName=OPTIONS
            """;

    @Test
    void shouldLoadTwoAccountsAndIndexByBothKeys() {

        AccountRegistry registry = AccountRegistry.parse(TWO_ACCOUNTS);

        assertEquals(2, registry.size());
        Optional<TraderAccount> alice = registry.findByAccountId("alice");
        assertTrue(alice.isPresent());
        assertEquals("111", alice.get().getTelegramChatId());
        assertEquals(50_000, alice.get().getCapitalPerTrade());
        assertEquals(5_000, alice.get().getMaxRiskPerTrade());
        assertEquals("EQUITY", alice.get().getDefaultMilestoneSetName());

        Optional<TraderAccount> aliceByChat = registry.findByTelegramChatId("111");
        assertTrue(aliceByChat.isPresent());
        assertSame(alice.get(), aliceByChat.get());

        Optional<TraderAccount> bob = registry.findByAccountId("bob");
        assertTrue(bob.isPresent());
        assertEquals(100_000, bob.get().getCapitalPerTrade());
        assertEquals(0, bob.get().getMaxRiskPerTrade());
    }

    @Test
    void shouldReturnEmptyForUnknownKeys() {

        AccountRegistry registry = AccountRegistry.parse(TWO_ACCOUNTS);

        assertTrue(registry.findByAccountId("carol").isEmpty());
        assertTrue(registry.findByTelegramChatId("999").isEmpty());
        assertTrue(registry.findByAccountId(null).isEmpty());
        assertTrue(registry.findByTelegramChatId(null).isEmpty());
    }

    @Test
    void shouldRejectDuplicateAccountIdWhenConstructedDirectly() {

        TraderAccount a = new TraderAccount(
                "alice", "111", "t", "k", "s", "https://example.com/cb", false, 50_000, 0, "EQUITY");
        TraderAccount b = new TraderAccount(
                "alice", "222", "t", "k", "s", "https://example.com/cb", false, 50_000, 0, "EQUITY");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AccountRegistry(List.of(a, b)));
        assertTrue(error.getMessage().contains("duplicate accountId"));
    }

    @Test
    void shouldRejectDuplicateTelegramChatIdWhenConstructedDirectly() {

        TraderAccount alice = new TraderAccount(
                "alice", "111", "t", "k", "s", "https://example.com/cb", false, 50_000, 0, "EQUITY");
        TraderAccount bob = new TraderAccount(
                "bob", "111", "t", "k", "s", "https://example.com/cb", false, 50_000, 0, "EQUITY");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AccountRegistry(List.of(alice, bob)));
        assertTrue(error.getMessage().contains("111"));
        assertTrue(error.getMessage().contains("alice"));
        assertTrue(error.getMessage().contains("bob"));
    }

    @Test
    void shouldRejectDuplicateTelegramChatIdInParsedConfig() {

        String conflicting = TWO_ACCOUNTS
                .replace("account.bob.telegramChatId=222", "account.bob.telegramChatId=111");

        assertThrows(IllegalStateException.class, () -> AccountRegistry.parse(conflicting));
    }

    @Test
    void shouldRejectEmptyRegistry() {

        assertThrows(IllegalStateException.class, () -> new AccountRegistry(List.of()));
    }

    @Test
    void shouldRejectMissingRequiredField() {

        String missingChatId = """
                account.alice.upstoxAnalyticsToken=t
                account.alice.upstoxApiKey=k
                account.alice.upstoxApiSecret=s
                account.alice.upstoxRedirectUri=https://example.com/cb
                account.alice.capitalPerTrade=50000
                account.alice.defaultMilestoneSetName=EQUITY
                """;

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AccountRegistry.parse(missingChatId));
        assertTrue(error.getMessage().contains("telegramChatId"));
    }

    @Test
    void shouldRejectNonNumericCapitalPerTrade() {

        String bad = TWO_ACCOUNTS
                .replace("account.alice.capitalPerTrade=50000", "account.alice.capitalPerTrade=not-a-number");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AccountRegistry.parse(bad));
        assertTrue(error.getMessage().contains("capitalPerTrade"));
    }

    @Test
    void shouldRejectUnknownMilestoneSetName() {

        String bad = TWO_ACCOUNTS
                .replace("account.alice.defaultMilestoneSetName=EQUITY",
                        "account.alice.defaultMilestoneSetName=NONSENSE");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AccountRegistry.parse(bad));
        assertTrue(error.getMessage().contains("NONSENSE"));
    }

    @Test
    void shouldRejectKeyOutsideAccountNamespace() {

        String bad = TWO_ACCOUNTS + "\nfeatures.somethingElse=true\n";

        assertThrows(IllegalStateException.class, () -> AccountRegistry.parse(bad));
    }

    @Test
    void shouldRejectMalformedAccountKey() {

        String bad = "account.alice=oops\n";

        assertThrows(IllegalStateException.class, () -> AccountRegistry.parse(bad));
    }

    @Test
    void shouldThrowWhenLoadingMissingFile() {

        Path missing = Path.of("/tmp/definitely-not-a-real-file-" + System.nanoTime() + ".properties");

        assertThrows(IllegalStateException.class, () -> AccountRegistry.load(missing));
    }

    @Test
    void shouldLoadFromDiskWhenFileExists() throws Exception {

        Path tmp = Files.createTempFile("accounts-", ".properties");
        try {
            Files.writeString(tmp, TWO_ACCOUNTS);

            AccountRegistry registry = AccountRegistry.load(tmp);

            assertEquals(2, registry.size());
            assertNotNull(registry.findByAccountId("alice").orElse(null));
            assertNotNull(registry.findByTelegramChatId("222").orElse(null));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void allShouldReturnAccountsSortedByAccountId() {

        String reversedOrder = """
                account.zack.telegramChatId=999
                account.zack.upstoxAnalyticsToken=t
                account.zack.upstoxApiKey=k
                account.zack.upstoxApiSecret=s
                account.zack.upstoxRedirectUri=https://example.com/cb
                account.zack.capitalPerTrade=50000
                account.zack.defaultMilestoneSetName=EQUITY

                account.alice.telegramChatId=111
                account.alice.upstoxAnalyticsToken=t
                account.alice.upstoxApiKey=k
                account.alice.upstoxApiSecret=s
                account.alice.upstoxRedirectUri=https://example.com/cb
                account.alice.capitalPerTrade=50000
                account.alice.defaultMilestoneSetName=EQUITY
                """;

        AccountRegistry registry = AccountRegistry.parse(reversedOrder);

        List<TraderAccount> ordered = List.copyOf(registry.all());
        assertEquals("alice", ordered.get(0).getAccountId());
        assertEquals("zack", ordered.get(1).getAccountId());
    }
}
