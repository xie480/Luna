package org.yilena.luna.constants;

/**
 * Redis 键常量类，统一维护会话上下文、降级标记和记忆热层相关键模板。
 */
public final class RedisKeyConstant {

    private RedisKeyConstant() {
    }

    /**
     * 用户会话上下文缓存键模板。
     */
    public static final String CONTEXT_KEY_PREFIX = "chat:context:%s";

    /**
     * 生成降级占位键。
     */
    public static final String GENERATE_FALLBACK_KEY = "chat:generate:fallback";

    /**
     * 记忆会话级缓存键模板。
     */
    public static final String MEMORY_SESSION_CACHE_KEY = "luna:memory:session:%s";

    /**
     * 工作记忆热层缓存键模板。
     */
    public static final String MEMORY_WORKING_CACHE_KEY = "luna:memory:working:%s";

    /**
     * 编译后上下文缓存键模板。
     */
    public static final String MEMORY_COMPILED_CONTEXT_KEY = "luna:memory:compiled:%s:%s";

    /**
     * 事件去重键模板。
     */
    public static final String MEMORY_EVENT_DEDUPE_KEY = "luna:memory:dedupe:%s:%s:%s";

    /**
     * 挂起工具调用缓存键模板。
     */
    public static final String MEMORY_PENDING_TOOL_CALL_KEY = "luna:memory:pending_tool:%s:%s";

    /**
     * 最近一次挂起工具调用缓存键模板。
     */
    public static final String MEMORY_PENDING_TOOL_CALL_LATEST_KEY = "luna:memory:pending_tool_latest:%s";

    /**
     * 挂起工具调用按任务索引键模板。
     */
    public static final String MEMORY_PENDING_TOOL_CALL_TASK_INDEX_KEY = "luna:memory:pending_tool_task:%s";
}
