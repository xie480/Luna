package org.yilena.runa.constants;

/*
    Redis Key常量
 */
public final class RedisKeyConstant {
    // 用户会话上下文Key
    public static final String SESSION_KEY_PREFIX = "chat:session:%s";
    // 生成降级占位Key
    public static final String GENERATE_FALLBACK_KEY = "chat:generate:fallback:%s";
}
