package com.kbquants.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for UpstoxAuthService's authorization URL construction.
 * <p>
 * Does NOT test exchangeCodeForToken, which makes a real network call
 * to Upstox -- that is out of scope for a unit test.
 */
class UpstoxAuthServiceTest {

    @Test
    void shouldBuildProductionAuthorizationUrlWithoutState() {

        UpstoxCredentials credentials = new UpstoxCredentials(
                "my-key", "my-secret", "https://example.com/callback", null, false);

        UpstoxAuthService authService = new UpstoxAuthService(credentials);

        String url = authService.buildAuthorizationUrl(null);

        assertEquals(
                "https://api.upstox.com/v2/login/authorization/dialog"
                        + "?client_id=my-key&redirect_uri=https%3A%2F%2Fexample.com%2Fcallback",
                url);
    }

    @Test
    void shouldBuildSandboxAuthorizationUrlWithState() {

        UpstoxCredentials credentials = new UpstoxCredentials(
                "my-key", "my-secret", "https://example.com/callback", null, true);

        UpstoxAuthService authService = new UpstoxAuthService(credentials);

        String url = authService.buildAuthorizationUrl("xyz state");

        assertEquals(
                "https://api-sandbox.upstox.com/v2/login/authorization/dialog"
                        + "?client_id=my-key&redirect_uri=https%3A%2F%2Fexample.com%2Fcallback"
                        + "&state=xyz+state",
                url);
    }
}
