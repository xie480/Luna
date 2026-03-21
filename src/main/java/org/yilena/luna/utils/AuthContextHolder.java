package org.yilena.luna.utils;

/**
 * 当前请求鉴权上下文
 * 用于在业务层获取 JWT 的 jti（作为稳定 sessionId）
 */
public final class AuthContextHolder {

    private static final ThreadLocal<String> SESSION_ID_HOLDER = new ThreadLocal<>();

    private AuthContextHolder() {
    }

    public static void setSessionId(String sessionId) {
        SESSION_ID_HOLDER.set(sessionId);
    }

    public static String getSessionId() {
        return SESSION_ID_HOLDER.get();
    }

    public static void clear() {
        SESSION_ID_HOLDER.remove();
    }
}
