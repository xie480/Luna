package org.yilena.luna.constants;

/**
 * 本地 MCP 工具常量类，负责统一维护本地工具实现类型、默认错误结果和核心工具名称，
 * 供本地工具适配与调用链路复用。
 */
public final class LocalToolConstants {

    private LocalToolConstants() {
    }

    /**
     * 本地处理器类型标识。
     */
    public static final String IMPL_TYPE_LOCAL_HANDLER = "LOCAL_HANDLER";
    /**
     * 本地工具结果序列化失败时返回的默认错误 JSON。
     */
    public static final String STATUS_ERROR_JSON = "{\"status\":\"error\",\"errorCode\":\"TOOL_SERIALIZE_ERROR\"}";
    /**
     * 记忆管理工具名。
     */
    public static final String TOOL_MANAGE_MEMORY = "manage_memory";
    /**
     * 日程管理工具名。
     */
    public static final String TOOL_MANAGE_SCHEDULE_TASK = "manage_schedule_task";
    /**
     * 知识库管理工具名。
     */
    public static final String TOOL_MANAGE_KNOWLEDGE_BASE = "manage_knowledge_base";
    /**
     * 日志管理工具名。
     */
    public static final String TOOL_MANAGE_LOG = "manage_log";
    /**
     * 网页搜索工具名。
     */
    public static final String TOOL_WEB_SEARCH = "web_search";
    /**
     * 图片搜索工具名。
     */
    public static final String TOOL_IMAGE_SEARCH = "image_search";
    /**
     * 新闻搜索工具名。
     */
    public static final String TOOL_NEWS_SEARCH = "news_search";
    /**
     * 图像识别搜索工具名。
     */
    public static final String TOOL_LENS_SEARCH = "lens_search";
    /**
     * 网页抓取工具名。
     */
    public static final String TOOL_WEB_SCRAPE = "web_scrape";
}
