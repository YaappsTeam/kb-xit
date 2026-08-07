package com.kbquants.live;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for UpstoxDataCredentials.
 */
class UpstoxDataCredentialsTest {

    @Test
    void shouldBuildCredentialsFromEnvironmentLookup() {

        Map<String, String> env = Map.of(
                "UPSTOX_ANALYTICS_TOKEN", "analytics123",
                "UPSTOX_SANDBOX", "true"
        );

        UpstoxDataCredentials credentials = UpstoxDataCredentials.fromEnv(env::get);

        assertEquals("analytics123", credentials.getAnalyticsToken());
        assertTrue(credentials.isSandbox());
    }

    @Test
    void shouldDefaultSandboxToFalseWhenNotSet() {

        Map<String, String> env = Map.of("UPSTOX_ANALYTICS_TOKEN", "analytics123");

        assertFalse(UpstoxDataCredentials.fromEnv(env::get).isSandbox());
    }

    /**
     * The Analytics Token is the only credential live market data needs --
     * no api key, secret or redirect uri, since there is no OAuth flow
     * involved in obtaining or presenting it.
     */
    @Test
    void shouldNotRequireOauthEnvironmentVariables() {

        Map<String, String> env = Map.of("UPSTOX_ANALYTICS_TOKEN", "analytics123");

        assertEquals("analytics123", UpstoxDataCredentials.fromEnv(env::get).getAnalyticsToken());
    }

    @Test
    void shouldThrowWhenTokenIsMissing() {
        assertThrows(IllegalStateException.class, () -> UpstoxDataCredentials.fromEnv(Map.<String, String>of()::get));
    }

    @Test
    void shouldThrowWhenTokenIsBlank() {

        Map<String, String> env = Map.of("UPSTOX_ANALYTICS_TOKEN", "   ");

        assertThrows(IllegalStateException.class, () -> UpstoxDataCredentials.fromEnv(env::get));
    }

    @Test
    void constructorShouldRejectBlankToken() {
        assertThrows(IllegalArgumentException.class, () -> new UpstoxDataCredentials("  ", false));
    }
}
