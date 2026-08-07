package com.kbquants.live;

import lombok.Getter;

import java.util.Objects;
import java.util.function.Function;

/**
 * Credentials for Upstox's read-only market data APIs, backed by an
 * Analytics Token.
 * <p>
 * Upstox issues two different tokens, and this class models the first:
 * <ul>
 *   <li><b>Analytics Token</b> (this class) -- valid for 1 year, generated
 *       once from the Developer Apps page with no OAuth redirect and no 2FA.
 *       Read-only. Covers Market Data and the realtime/streaming WebSocket,
 *       with no static-IP requirement.</li>
 *   <li><b>Standard access token</b> ({@link UpstoxCredentials}) -- expires
 *       at 3:30 AM the next day and needs the interactive authorization-code
 *       flow. Required for placing or modifying orders.</li>
 * </ul>
 * Because streaming prices needs only the former, live market data requires
 * no daily login -- only order placement does. That is why this class does
 * not carry an apiKey/apiSecret/redirectUri: none of them are used to obtain
 * or present an Analytics Token.
 * <p>
 * The token is a long-lived credential granting read access to the account's
 * market data, so it is read from the environment and never committed --
 * see CODING_STANDARDS.md section 7.
 */
@Getter
public final class UpstoxDataCredentials {

    private final String analyticsToken;
    private final boolean sandbox;

    public UpstoxDataCredentials(String analyticsToken, boolean sandbox) {
        this.analyticsToken = Objects.requireNonNull(analyticsToken, "analyticsToken must not be null");
        if (analyticsToken.isBlank()) {
            throw new IllegalArgumentException("analyticsToken must not be blank");
        }
        this.sandbox = sandbox;
    }

    /**
     * Reads UPSTOX_ANALYTICS_TOKEN (required) and UPSTOX_SANDBOX (optional,
     * "true"/"false", default false) from the process environment.
     */
    public static UpstoxDataCredentials fromEnv() {
        return fromEnv(System::getenv);
    }

    static UpstoxDataCredentials fromEnv(Function<String, String> env) {
        return new UpstoxDataCredentials(
                requireEnv(env, "UPSTOX_ANALYTICS_TOKEN"),
                Boolean.parseBoolean(env.apply("UPSTOX_SANDBOX")));
    }

    private static String requireEnv(Function<String, String> env, String name) {
        String value = env.apply(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }
}
