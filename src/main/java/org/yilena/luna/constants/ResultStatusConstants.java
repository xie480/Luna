package org.yilena.luna.constants;

/**
 * 结果状态常量类，负责统一维护接口响应和工具载荷中常见的标准状态值，
 * 避免状态字符串在各处重复定义。
 */
public final class ResultStatusConstants {

    private ResultStatusConstants() {
    }

    /**
     * 成功状态值。
     */
    public static final String SUCCESS = "success";
    /**
     * 错误状态值。
     */
    public static final String ERROR = "error";
    /**
     * 未授权状态值。
     */
    public static final String UNAUTHORIZED = "unauthorized";
    /**
     * 拒绝状态值。
     */
    public static final String REJECTED = "rejected";
}
