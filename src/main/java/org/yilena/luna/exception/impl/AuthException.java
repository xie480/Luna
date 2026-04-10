package org.yilena.luna.exception.impl;

/**
 * 鉴权异常，用于标识登录态、令牌或权限校验失败，并交由全局异常处理链统一包装响应。
 */
public class AuthException extends RuntimeException {

    public AuthException(String message) {
        /**
         * 直接透传鉴权失败原因，便于上层异常处理器按统一格式输出错误信息。
         */
        super(message);
    }
}
