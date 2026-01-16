package org.yilena.runa.constants;

/*
    Redis Key常量
 */
public final class RedisKeyConstant {
    // 用户会话上下文Key
    public static final String CONTEXT_KEY_PREFIX = "chat:context:%s";
    // 生成降级占位Key
    public static final String GENERATE_FALLBACK_KEY = "chat:generate:fallback";
}
