package com.kbquants.live;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for UpstoxCredentials.
 */
class UpstoxCredentialsTest {

    @Test
    void shouldBuildCredentialsFromEnvironmentLookup() {

        Map<String, String> env = Map.of(
                "UPSTOX_API_KEY", "key123",
                "UPSTOX_API_SECRET", "secret123",
                "UPSTOX_REDIRECT_URI", "https://example.com/callback",
                "UPSTOX_ACCESS_TOKEN", "token123",
                "UPSTOX_SANDBOX", "true"
        );

        UpstoxCredentials credentials = UpstoxCredentials.fromEnv(env::get);

        assertEquals("key123", credentials.getApiKey());
        assertEquals("secret123", credentials.getApiSecret());
        assertEquals("https://example.com/callback", credentials.getRedirectUri());
        assertEquals("token123", credentials.getAccessToken());
        assertTrue(credentials.isSandbox());
        assertTrue(credentials.hasAccessToken());
    }

    @Test
    void shouldDefaultSandboxToFalseWhenNotSet() {

        Map<String, String> env = Map.of(
                "UPSTOX_API_KEY", "key123",
                "UPSTOX_API_SECRET", "secret123",
                "UPSTOX_REDIRECT_URI", "https://example.com/callback"
        );

        UpstoxCredentials credentials = UpstoxCredentials.fromEnv(env::get);

        assertFalse(credentials.isSandbox());
        assertFalse(credentials.hasAccessToken());
    }

    @Test
    void shouldThrowWhenRequiredEnvVarIsMissing() {

        Map<String, String> env = Map.of(
                "UPSTOX_API_KEY", "key123",
                "UPSTOX_REDIRECT_URI", "https://example.com/callback"
        );

        assertThrows(IllegalStateException.class, () -> UpstoxCredentials.fromEnv(env::get));
    }

    @Test
    void hasAccessTokenShouldBeFalseForBlankToken() {

        UpstoxCredentials credentials = new UpstoxCredentials(
                "key", "secret", "https://example.com", "   ", false);

        assertFalse(credentials.hasAccessToken());
    }
}
