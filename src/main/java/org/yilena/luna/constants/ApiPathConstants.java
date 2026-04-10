package org.yilena.luna.constants;

/**
 * API 路径常量类，负责集中维护认证、状态流和 Swagger 相关的固定路径片段。
 */
public final class ApiPathConstants {

    /**
     * 私有构造方法，禁止外部实例化常量类。
     */
    private ApiPathConstants() {
    }

    /**
     * 登录接口路径。
     */
    public static final String AUTH_LOGIN = "/auth/login";
    /**
     * Luna 状态 SSE 推送接口路径。
     */
    public static final String LUNA_STATUS_STREAM = "/api/luna/status/stream";
    /**
     * Swagger UI HTML 入口路径。
     */
    public static final String SWAGGER_UI_HTML = "/swagger-ui.html";
    /**
     * Swagger 依赖的 WebJars 静态资源路径。
     */
    public static final String WEBJARS_ALL = "/webjars/**";
    /**
     * Swagger UI 静态资源路径。
     */
    public static final String SWAGGER_UI_ALL = "/swagger-ui/**";
    /**
     * OpenAPI 文档接口路径。
     */
    public static final String API_DOCS_ALL = "/v3/api-docs/**";
}
