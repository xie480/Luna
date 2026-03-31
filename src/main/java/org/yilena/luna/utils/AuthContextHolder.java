package org.yilena.luna.utils;

public final class AuthContextHolder {

    private static final ThreadLocal<String> SESSION_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> PRINCIPAL_KEY_HOLDER = new ThreadLocal<>();

    private AuthContextHolder() {
    }

    public static void setSessionId(String sessionId) {
        SESSION_ID_HOLDER.set(sessionId);
    }

    public static String getSessionId() {
        return SESSION_ID_HOLDER.get();
    }

    public static void setPrincipalKey(String principalKey) {
        PRINCIPAL_KEY_HOLDER.set(principalKey);
    }

    public static String getPrincipalKey() {
        return PRINCIPAL_KEY_HOLDER.get();
    }

    public static void clear() {
        SESSION_ID_HOLDER.remove();
        PRINCIPAL_KEY_HOLDER.remove();
    }
}
