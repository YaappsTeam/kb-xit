package com.kbquants.live;

import lombok.Getter;

import java.util.Objects;
import java.util.function.Function;

/**
 * Credentials/config needed to talk to the Upstox API.
 * <p>
 * accessToken is the short-lived, daily-expiring token obtained via the
 * OAuth authorization-code flow (see UpstoxAuthService) -- it is not
 * present until that flow has been completed for the current trading day.
 */
@Getter
public final class UpstoxCredentials {

    private final String apiKey;
    private final String apiSecret;
    private final String redirectUri;
    private final String accessToken;
    private final boolean sandbox;

    public UpstoxCredentials(String apiKey, String apiSecret, String redirectUri, String accessToken, boolean sandbox) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.apiSecret = Objects.requireNonNull(apiSecret, "apiSecret must not be null");
        this.redirectUri = Objects.requireNonNull(redirectUri, "redirectUri must not be null");
        this.accessToken = accessToken;
        this.sandbox = sandbox;
    }

    public boolean hasAccessToken() {
        return accessToken != null && !accessToken.isBlank();
    }

    /**
     * Reads UPSTOX_API_KEY, UPSTOX_API_SECRET, UPSTOX_REDIRECT_URI (required),
     * UPSTOX_ACCESS_TOKEN (optional -- obtained separately via UpstoxAuthService)
     * and UPSTOX_SANDBOX (optional, "true"/"false", default false) from the process environment.
     */
    public static UpstoxCredentials fromEnv() {
        return fromEnv(System::getenv);
    }

    static UpstoxCredentials fromEnv(Function<String, String> env) {
        String apiKey = requireEnv(env, "UPSTOX_API_KEY");
        String apiSecret = requireEnv(env, "UPSTOX_API_SECRET");
        String redirectUri = requireEnv(env, "UPSTOX_REDIRECT_URI");
        String accessToken = env.apply("UPSTOX_ACCESS_TOKEN");
        boolean sandbox = Boolean.parseBoolean(env.apply("UPSTOX_SANDBOX"));
        return new UpstoxCredentials(apiKey, apiSecret, redirectUri, accessToken, sandbox);
    }

    private static String requireEnv(Function<String, String> env, String name) {
        String value = env.apply(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }
}
