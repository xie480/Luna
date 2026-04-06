package org.yilena.luna.constants;

/**
 * Auth related fixed values.
 */
public final class AuthConstants {

    private AuthConstants() {
    }

    public static final String REQUEST_ATTR_SESSION_ID = "SESSION_ID";
    public static final String REQUEST_ATTR_PRINCIPAL_KEY = "PRINCIPAL_KEY";
    public static final String UNAUTHORIZED_MESSAGE = "unauthorized";
    public static final String INVALID_TOKEN_MESSAGE = "invalid token";
    public static final String DEFAULT_JWT_SECRET = "change-me-in-production";

    public static final String UNAUTHORIZED_RESPONSE_JSON =
            "{\"status\":\"unauthorized\",\"message\":\"unauthorized\"}";
    public static final String INVALID_TOKEN_RESPONSE_JSON =
            "{\"status\":\"unauthorized\",\"message\":\"invalid token\"}";
}
