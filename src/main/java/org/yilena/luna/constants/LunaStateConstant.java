package org.yilena.luna.constants;

/**
 * Luna 状态与提示语常量类
 */
public final class LunaStateConstant {

    private LunaStateConstant() {}

    // ================= 状态标识码 (Status) =================
    public static final String STATUS_WORKING = "WORKING";
    public static final String STATUS_PREFERENCE = "PREFERENCE";
    public static final String STATUS_MEMORY = "MEMORY";
    public static final String STATUS_SCHEDULE = "SCHEDULE";
    public static final String STATUS_SEARCHING = "SEARCHING";
    public static final String STATUS_SCRAPING = "SCRAPING";
    public static final String STATUS_KNOWLEDGE_BASE = "KNOWLEDGE_BASE";
    public static final String STATUS_LOG = "LOG";
    public static final String STATUS_THINKING = "THINKING";
    public static final String STATUS_IDLE = "IDLE";
    public static final String STATUS_RETRIEVING = "RETRIEVING";
    public static final String STATUS_STARTING = "STARTING";

    // 新增：OpenClaw / CodeOps / Desktop / Lock / Report 统一状态
    public static final String STATUS_PLAN = "PLAN";
    public static final String STATUS_CODEOPS = "CODEOPS";
    public static final String STATUS_DESKTOP = "DESKTOP";
    public static final String STATUS_LOCK = "LOCK";
    public static final String STATUS_REPORT = "REPORT";

    // ================= 状态提示文本 (Value) =================
    public static final String VALUE_PREFERENCE = "Luna 正在记录主人的偏好...";
    public static final String VALUE_MEMORY = "Luna 正在回忆过去的点点滴滴...";
    public static final String VALUE_SCHEDULE = "Luna 正在安排日程任务...";
    public static final String VALUE_SEARCH_WEB = "Luna 正在全网搜索最新资讯...";
    public static final String VALUE_SEARCH_IMAGES = "Luna 正在搜索相关图片...";
    public static final String VALUE_SEARCH_NEWS = "Luna 正在查阅最新新闻...";
    public static final String VALUE_SEARCH_LENS = "Luna 正在进行以图搜图...";
    public static final String VALUE_SCRAPE_WEB = "Luna 正在抓取网页内容...";
    public static final String VALUE_KNOWLEDGE_BASE = "Luna 正在查阅或整理本地知识库...";
    public static final String VALUE_LOG = "Luna 正在查阅系统日志...";
    public static final String VALUE_THINKING = "Luna 正在思考...";
    public static final String VALUE_THINKING_ORGANIZE = "Luna 正在组织语言...";
    public static final String VALUE_IDLE = "";
    public static final String VALUE_RETRIEVING = "Luna 正在翻阅本地记忆与知识库...";
    public static final String VALUE_STARTING = "Luna 正在苏醒...";

    // 新增：统一状态文案
    public static final String VALUE_PLAN = "Luna 正在执行编排计划...";
    public static final String VALUE_CODEOPS = "Luna 正在处理代码工程任务...";
    public static final String VALUE_DESKTOP = "Luna 正在执行桌面能力任务...";
    public static final String VALUE_LOCK = "Luna 正在处理执行锁...";
    public static final String VALUE_REPORT = "Luna 正在生成任务报告...";
}
