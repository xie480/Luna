package org.yilena.luna.exception.impl;

/**
 * AuthException ??
 */
public class AuthException extends RuntimeException {
    public AuthException(String message) {
        // 透传鉴权错误信息，交由全局异常处理器统一包装响应。
        super(message);
    }
}
