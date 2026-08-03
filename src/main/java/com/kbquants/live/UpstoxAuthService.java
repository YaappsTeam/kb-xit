package com.kbquants.live;

import com.upstox.ApiClient;
import com.upstox.ApiException;
import io.swagger.client.api.LoginApi;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Handles the once-per-trading-day Upstox OAuth2 authorization-code flow.
 * <p>
 * Upstox access tokens expire daily and require an interactive browser login
 * (including 2FA/TOTP) that cannot be automated headlessly. The flow is:
 * <ol>
 *   <li>Send the user to the URL from {@link #buildAuthorizationUrl(String)}.</li>
 *   <li>After login, Upstox redirects to the configured redirect URI with a
 *       {@code ?code=...} query parameter.</li>
 *   <li>Pass that code to {@link #exchangeCodeForToken(String)} to obtain the
 *       access token to use for the rest of the trading day.</li>
 * </ol>
 */
public class UpstoxAuthService {

    private static final String API_VERSION = "2.0";
    private static final String GRANT_TYPE = "authorization_code";

    private final UpstoxCredentials credentials;
    private final LoginApi loginApi;

    public UpstoxAuthService(UpstoxCredentials credentials) {
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.loginApi = new LoginApi(new ApiClient(credentials.isSandbox()));
    }

    public String buildAuthorizationUrl(String state) {
        String base = credentials.isSandbox() ? "https://api-sandbox.upstox.com" : "https://api.upstox.com";

        StringBuilder url = new StringBuilder(base)
                .append("/v2/login/authorization/dialog")
                .append("?client_id=").append(encode(credentials.getApiKey()))
                .append("&redirect_uri=").append(encode(credentials.getRedirectUri()));

        if (state != null && !state.isBlank()) {
            url.append("&state=").append(encode(state));
        }

        return url.toString();
    }

    public String exchangeCodeForToken(String authorizationCode) throws ApiException {
        Objects.requireNonNull(authorizationCode, "authorizationCode must not be null");

        return loginApi.token(
                API_VERSION,
                authorizationCode,
                credentials.getApiKey(),
                credentials.getApiSecret(),
                credentials.getRedirectUri(),
                GRANT_TYPE
        ).getAccessToken();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
