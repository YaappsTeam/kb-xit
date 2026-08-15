package com.kbquants.domain;

import lombok.Getter;

import java.util.Objects;

/**
 * One registered trader's configuration.
 * <p>
 * Immutable and per-account: the process runs one of these per person it
 * manages trades for, and nothing about it changes without a config
 * reload. Credentials that vary within a day -- the daily order token --
 * are supplied at runtime via /token and are deliberately not held here,
 * for the same reason UpstoxCredentials leaves accessToken nullable.
 * <p>
 * The two identity fields serve different purposes and must not be
 * conflated:
 * <ul>
 *   <li>{@code accountId} is the operator-facing handle used in config,
 *       logs and trade file names. It is a short opaque string
 *       ("alice", "bob"), never a phone number or a Telegram id.</li>
 *   <li>{@code telegramChatId} is what the bot dispatches inbound
 *       commands against. It comes from Telegram, is a numeric string,
 *       and is the ONLY key allowed to authorise a command as belonging
 *       to this account -- accepting an account id from message text
 *       would let one trader act on another's trades.</li>
 * </ul>
 * <p>
 * See PRODUCT_REQUIREMENTS.md §F8 (multi-account isolation) and
 * IMPLEMENTATION_PLAN.md Phase 3.5 for the design this class starts.
 */
@Getter
public final class TraderAccount {

    private final String accountId;
    private final String telegramChatId;
    private final String upstoxAnalyticsToken;
    private final String upstoxApiKey;
    private final String upstoxApiSecret;
    private final String upstoxRedirectUri;
    private final boolean upstoxSandbox;
    private final double capitalPerTrade;
    private final double maxRiskPerTrade;
    private final String defaultMilestoneSetName;

    public TraderAccount(String accountId,
                         String telegramChatId,
                         String upstoxAnalyticsToken,
                         String upstoxApiKey,
                         String upstoxApiSecret,
                         String upstoxRedirectUri,
                         boolean upstoxSandbox,
                         double capitalPerTrade,
                         double maxRiskPerTrade,
                         String defaultMilestoneSetName) {
        this.accountId = requireNonBlank(accountId, "accountId");
        this.telegramChatId = requireNonBlank(telegramChatId, "telegramChatId");
        this.upstoxAnalyticsToken = requireNonBlank(upstoxAnalyticsToken, "upstoxAnalyticsToken");
        this.upstoxApiKey = requireNonBlank(upstoxApiKey, "upstoxApiKey");
        this.upstoxApiSecret = requireNonBlank(upstoxApiSecret, "upstoxApiSecret");
        this.upstoxRedirectUri = requireNonBlank(upstoxRedirectUri, "upstoxRedirectUri");
        this.upstoxSandbox = upstoxSandbox;
        if (capitalPerTrade <= 0) {
            throw new IllegalArgumentException(
                    "capitalPerTrade must be positive for account " + accountId + ": " + capitalPerTrade);
        }
        this.capitalPerTrade = capitalPerTrade;
        if (maxRiskPerTrade < 0) {
            throw new IllegalArgumentException(
                    "maxRiskPerTrade must not be negative for account " + accountId + ": " + maxRiskPerTrade);
        }
        this.maxRiskPerTrade = maxRiskPerTrade;
        this.defaultMilestoneSetName = requireNonBlank(defaultMilestoneSetName, "defaultMilestoneSetName");
    }

    /** True when a per-trade risk ceiling is set; false means "capital alone caps the position." */
    public boolean hasMaxRiskPerTrade() {
        return maxRiskPerTrade > 0;
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
