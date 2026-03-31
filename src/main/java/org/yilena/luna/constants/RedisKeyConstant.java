package org.yilena.luna.constants;

/*
    Redis Key常量
 */
public final class RedisKeyConstant {
    private RedisKeyConstant() {
    }

    // 用户会话上下文Key
    public static final String CONTEXT_KEY_PREFIX = "chat:context:%s";
    // 生成降级占位Key
    public static final String GENERATE_FALLBACK_KEY = "chat:generate:fallback";

    // Memory v2 hot-layer keys
    public static final String MEMORY_SESSION_CACHE_KEY = "luna:memory:session:%s";
    public static final String MEMORY_WORKING_CACHE_KEY = "luna:memory:working:%s";
    public static final String MEMORY_COMPILED_CONTEXT_KEY = "luna:memory:compiled:%s:%s";
    public static final String MEMORY_EVENT_DEDUPE_KEY = "luna:memory:dedupe:%s:%s:%s";
    public static final String MEMORY_PENDING_TOOL_CALL_KEY = "luna:memory:pending_tool:%s:%s";
    public static final String MEMORY_PENDING_TOOL_CALL_LATEST_KEY = "luna:memory:pending_tool_latest:%s";
    public static final String MEMORY_PENDING_TOOL_CALL_TASK_INDEX_KEY = "luna:memory:pending_tool_task:%s";
}
