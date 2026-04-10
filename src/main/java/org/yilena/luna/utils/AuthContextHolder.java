package org.yilena.luna.utils;

/**
 * 该线程上下文持有器用于在单次请求链路中缓存会话标识和主体标识，便于鉴权与能力路由复用。
 */
public final class AuthContextHolder {

    /**
     * 当前线程绑定的会话标识。
     */
    private static final ThreadLocal<String> SESSION_ID_HOLDER = new ThreadLocal<>();
    /**
     * 当前线程绑定的主体标识。
     */
    private static final ThreadLocal<String> PRINCIPAL_KEY_HOLDER = new ThreadLocal<>();

    private AuthContextHolder() {
    }

    /**
     * 写入当前线程的会话标识。
     */
    public static void setSessionId(String sessionId) {
        SESSION_ID_HOLDER.set(sessionId);
    }

    /**
     * 读取当前线程的会话标识。
     */
    public static String getSessionId() {
        return SESSION_ID_HOLDER.get();
    }

    /**
     * 写入当前线程的主体标识。
     */
    public static void setPrincipalKey(String principalKey) {
        PRINCIPAL_KEY_HOLDER.set(principalKey);
    }

    /**
     * 读取当前线程的主体标识。
     */
    public static String getPrincipalKey() {
        return PRINCIPAL_KEY_HOLDER.get();
    }

    /**
     * 清理当前线程中的鉴权上下文，避免线程复用时发生身份串扰。
     */
    public static void clear() {
        SESSION_ID_HOLDER.remove();
        PRINCIPAL_KEY_HOLDER.remove();
    }
}
