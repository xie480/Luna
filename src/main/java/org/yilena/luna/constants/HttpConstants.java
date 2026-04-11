package org.yilena.luna.constants;

/**
 * HTTP 协议常量类，负责统一维护常用请求头、内容类型和认证前缀字面量，
 * 避免网络调用和接口处理时重复硬编码。
 */
public final class HttpConstants {

    private HttpConstants() {
    }

    /**
     * 认证请求头名称。
     */
    public static final String HEADER_AUTHORIZATION = "Authorization";
    /**
     * 内容类型请求头名称。
     */
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    /**
     * Accept 请求头名称。
     */
    public static final String HEADER_ACCEPT = "Accept";
    /**
     * JSON 内容类型。
     */
    public static final String CONTENT_TYPE_JSON = "application/json";
    /**
     * 带 UTF-8 编码声明的 JSON 内容类型。
     */
    public static final String CONTENT_TYPE_JSON_UTF8 = "application/json;charset=UTF-8";
    /**
     * Bearer 令牌前缀。
     */
    public static final String BEARER_PREFIX = "Bearer ";
    /**
     * Basic 认证前缀。
     */
    public static final String BASIC_PREFIX = "Basic ";
}
