package com.kbquants.session;

import com.kbquants.domain.MilestoneSets;
import com.kbquants.domain.TraderAccount;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The set of trader accounts the process is configured to manage, indexed
 * both by {@code accountId} (operator handle) and by {@code telegramChatId}
 * (routing key for inbound commands).
 * <p>
 * Loaded once at startup from a properties file and never mutated -- adding
 * an account is a config change followed by a restart, deliberately not a
 * live operation. Ten known traders do not need runtime membership churn,
 * and adding it would open questions (what happens to trades in flight
 * when their account is removed?) that are not worth answering yet.
 * <p>
 * Duplicate accountId or telegramChatId across two entries is fatal: silent
 * winner-takes-all would route one trader's commands to another's
 * TradeMonitor, which is the exact failure this class exists to prevent.
 * <p>
 * File format ({@code accounts.properties}), one line per field:
 * <pre>
 * account.&lt;accountId&gt;.telegramChatId=&lt;numeric chat id&gt;
 * account.&lt;accountId&gt;.upstoxAnalyticsToken=&lt;year-valid, read-only&gt;
 * account.&lt;accountId&gt;.upstoxApiKey=&lt;OAuth app key&gt;
 * account.&lt;accountId&gt;.upstoxApiSecret=&lt;OAuth app secret&gt;
 * account.&lt;accountId&gt;.upstoxRedirectUri=&lt;OAuth redirect&gt;
 * account.&lt;accountId&gt;.upstoxSandbox=false        (optional, default false)
 * account.&lt;accountId&gt;.capitalPerTrade=50000
 * account.&lt;accountId&gt;.maxRiskPerTrade=5000        (optional, 0 disables)
 * account.&lt;accountId&gt;.defaultMilestoneSetName=EQUITY
 * </pre>
 * Chosen over YAML because the shape is flat, this repo already relies on
 * JDK built-ins where practical (CODING_STANDARDS.md §1), and Properties
 * lets 10 accounts share one file without pulling in a parser dependency.
 */
@Slf4j
public final class AccountRegistry {

    private final Map<String, TraderAccount> byAccountId;
    private final Map<String, TraderAccount> byTelegramChatId;

    public AccountRegistry(Collection<TraderAccount> accounts) {
        TreeMap<String, TraderAccount> byId = new TreeMap<>();
        LinkedHashMap<String, TraderAccount> byChat = new LinkedHashMap<>();
        for (TraderAccount account : accounts) {
            TraderAccount previousId = byId.putIfAbsent(account.getAccountId(), account);
            if (previousId != null) {
                throw new IllegalStateException(
                        "duplicate accountId in registry: " + account.getAccountId());
            }
            TraderAccount previousChat = byChat.putIfAbsent(account.getTelegramChatId(), account);
            if (previousChat != null) {
                throw new IllegalStateException(
                        "telegramChatId " + account.getTelegramChatId()
                                + " is registered to both '" + previousChat.getAccountId()
                                + "' and '" + account.getAccountId() + "'");
            }
        }
        if (byId.isEmpty()) {
            throw new IllegalStateException("account registry is empty; at least one account is required");
        }
        // Map.copyOf would give a hash-backed Map that loses the TreeMap
        // ordering, so all() would return accounts in an unstable order.
        // Unmodifiable views over the built maps preserve the sort.
        this.byAccountId = Collections.unmodifiableMap(byId);
        this.byTelegramChatId = Collections.unmodifiableMap(byChat);
    }

    public Optional<TraderAccount> findByAccountId(String accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byAccountId.get(accountId));
    }

    public Optional<TraderAccount> findByTelegramChatId(String telegramChatId) {
        if (telegramChatId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byTelegramChatId.get(telegramChatId));
    }

    /**
     * All registered accounts, sorted alphabetically by {@code accountId}.
     * <p>
     * Sorted (not config-file order) because {@link Properties} does not
     * preserve iteration order, and letting the ordering depend on hashing
     * would make status displays flicker between runs. Alphabetical is
     * deterministic and cheap.
     */
    public Collection<TraderAccount> all() {
        return byAccountId.values();
    }

    public int size() {
        return byAccountId.size();
    }

    /**
     * Loads {@code accounts.properties} from the given path.
     * <p>
     * Missing file is a fatal startup error rather than "start empty": the
     * process has no useful behaviour without accounts, and silently
     * booting with none would hide a config mistake behind a bot that
     * simply refuses every command.
     */
    public static AccountRegistry load(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("account registry file not found or unreadable: " + path);
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            return parse(reader);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read account registry file: " + path, e);
        }
    }

    /** Test-facing overload: parse from an in-memory string. */
    static AccountRegistry parse(String propertiesText) {
        return parse(new StringReader(propertiesText));
    }

    private static AccountRegistry parse(Reader reader) {
        Properties properties = new Properties();
        try {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("failed to parse account registry properties", e);
        }
        return new AccountRegistry(buildAccounts(properties));
    }

    private static Collection<TraderAccount> buildAccounts(Properties properties) {
        Map<String, Map<String, String>> fieldsByAccount = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("account.")) {
                throw new IllegalStateException(
                        "unexpected key in account registry (must start with 'account.'): " + key);
            }
            String rest = key.substring("account.".length());
            int dot = rest.indexOf('.');
            if (dot <= 0 || dot == rest.length() - 1) {
                throw new IllegalStateException(
                        "malformed account key (expected 'account.<id>.<field>'): " + key);
            }
            String accountId = rest.substring(0, dot);
            String field = rest.substring(dot + 1);
            fieldsByAccount.computeIfAbsent(accountId, k -> new LinkedHashMap<>())
                    .put(field, properties.getProperty(key));
        }
        List<TraderAccount> accounts = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : fieldsByAccount.entrySet()) {
            accounts.add(buildOne(entry.getKey(), entry.getValue()));
        }
        return accounts;
    }

    private static TraderAccount buildOne(String accountId, Map<String, String> fields) {
        String defaultSet = requireField(accountId, fields, "defaultMilestoneSetName");
        if (MilestoneSets.byName(defaultSet).isEmpty()) {
            throw new IllegalStateException(
                    "account '" + accountId + "' names unknown milestone set: " + defaultSet
                            + " (available: " + MilestoneSets.names() + ")");
        }
        return new TraderAccount(
                accountId,
                requireField(accountId, fields, "telegramChatId"),
                requireField(accountId, fields, "upstoxAnalyticsToken"),
                requireField(accountId, fields, "upstoxApiKey"),
                requireField(accountId, fields, "upstoxApiSecret"),
                requireField(accountId, fields, "upstoxRedirectUri"),
                Boolean.parseBoolean(fields.getOrDefault("upstoxSandbox", "false")),
                parseDouble(accountId, fields, "capitalPerTrade", true),
                parseDouble(accountId, fields, "maxRiskPerTrade", false),
                defaultSet);
    }

    private static String requireField(String accountId, Map<String, String> fields, String field) {
        String value = fields.get(field);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "account '" + accountId + "' is missing required field: " + field);
        }
        return value;
    }

    private static double parseDouble(String accountId, Map<String, String> fields, String field, boolean required) {
        String value = fields.get(field);
        if (value == null || value.isBlank()) {
            if (required) {
                throw new IllegalStateException(
                        "account '" + accountId + "' is missing required numeric field: " + field);
            }
            return 0.0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "account '" + accountId + "' has non-numeric " + field + ": " + value, e);
        }
    }
}
