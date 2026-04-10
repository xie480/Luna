package org.yilena.luna.constants;

/**
 * 认证常量类，负责集中维护鉴权流程中的请求属性、默认文案和默认密钥配置。
 */
public final class AuthConstants {

    /**
     * 私有构造方法，禁止外部实例化常量类。
     */
    private AuthConstants() {
    }

    /**
     * 请求上下文中保存会话 ID 的属性名。
     */
    public static final String REQUEST_ATTR_SESSION_ID = "SESSION_ID";
    /**
     * 请求上下文中保存登录主体标识的属性名。
     */
    public static final String REQUEST_ATTR_PRINCIPAL_KEY = "PRINCIPAL_KEY";
    /**
     * 未授权场景的默认提示文案。
     */
    public static final String UNAUTHORIZED_MESSAGE = "unauthorized";
    /**
     * 令牌非法场景的默认提示文案。
     */
    public static final String INVALID_TOKEN_MESSAGE = "invalid token";
    /**
     * 未配置 JWT 密钥时使用的默认密钥，仅适用于开发或兜底场景。
     */
    public static final String DEFAULT_JWT_SECRET = "change-me-in-production";

    /**
     * 未授权场景返回给前端的固定 JSON 响应体。
     */
    public static final String UNAUTHORIZED_RESPONSE_JSON =
            "{\"status\":\"unauthorized\",\"message\":\"unauthorized\"}";
    /**
     * 令牌非法场景返回给前端的固定 JSON 响应体。
     */
    public static final String INVALID_TOKEN_RESPONSE_JSON =
            "{\"status\":\"unauthorized\",\"message\":\"invalid token\"}";
}
