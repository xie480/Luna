package org.yilena.luna.utils;

/**
 * 当前请求鉴权上下文
 * 用于在业务层获取 JWT 的 jti（作为稳定 sessionId）
 */
public final class AuthContextHolder {

    private static final ThreadLocal<String> SESSION_ID_HOLDER = new ThreadLocal<>(); // 定义方法签名

    private AuthContextHolder() { // 定义方法签名
    } // 结束当前代码块

    public static void setSessionId(String sessionId) { // 定义方法签名
        SESSION_ID_HOLDER.set(sessionId); // 执行语句逻辑
    } // 结束当前代码块

    public static String getSessionId() { // 定义方法签名
        return SESSION_ID_HOLDER.get(); // 返回处理结果
    } // 结束当前代码块

    public static void clear() { // 定义方法签名
        SESSION_ID_HOLDER.remove(); // 执行语句逻辑
    } // 结束当前代码块
} // 结束当前代码块
