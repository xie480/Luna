package org.yilena.luna.constants;

/**
 * Luna 状态与提示语常量类
 */
public final class LunaStateConstant {

    private LunaStateConstant() {}

    /**
     * 通用工作中状态标识，表示 Luna 正在执行当前任务。
     */
    public static final String STATUS_WORKING = "WORKING";
    /**
     * 偏好写入状态标识，表示正在处理用户偏好相关信息。
     */
    public static final String STATUS_PREFERENCE = "PREFERENCE";
    /**
     * 记忆处理状态标识，表示正在写入或检索记忆数据。
     */
    public static final String STATUS_MEMORY = "MEMORY";
    /**
     * 日程处理状态标识，表示正在安排或更新日程任务。
     */
    public static final String STATUS_SCHEDULE = "SCHEDULE";
    /**
     * 网络搜索状态标识，表示正在执行外部搜索查询。
     */
    public static final String STATUS_SEARCHING = "SEARCHING";
    /**
     * 网页抓取状态标识，表示正在拉取页面正文内容。
     */
    public static final String STATUS_SCRAPING = "SCRAPING";
    /**
     * 知识库处理状态标识，表示正在读写本地知识库。
     */
    public static final String STATUS_KNOWLEDGE_BASE = "KNOWLEDGE_BASE";
    /**
     * 日志处理状态标识，表示正在查询或记录系统日志。
     */
    public static final String STATUS_LOG = "LOG";
    /**
     * 思考状态标识，表示主模型正在组织推理过程。
     */
    public static final String STATUS_THINKING = "THINKING";
    /**
     * 空闲状态标识，表示当前没有进行中的任务。
     */
    public static final String STATUS_IDLE = "IDLE";
    /**
     * 检索状态标识，表示正在联合读取记忆与知识上下文。
     */
    public static final String STATUS_RETRIEVING = "RETRIEVING";
    /**
     * 启动状态标识，表示系统仍处于初始化阶段。
     */
    public static final String STATUS_STARTING = "STARTING";

    /**
     * 计划编排状态标识，表示正在执行 OpenClaw 计划链路。
     */
    public static final String STATUS_PLAN = "PLAN";
    /**
     * 代码工程状态标识，表示正在处理代码分析或修改任务。
     */
    public static final String STATUS_CODEOPS = "CODEOPS";
    /**
     * 桌面能力状态标识，表示正在执行桌面自动化操作。
     */
    public static final String STATUS_DESKTOP = "DESKTOP";
    /**
     * 执行锁状态标识，表示正在等待或处理互斥锁控制。
     */
    public static final String STATUS_LOCK = "LOCK";
    /**
     * 报告生成状态标识，表示正在汇总任务执行结果。
     */
    public static final String STATUS_REPORT = "REPORT";

    /**
     * 偏好处理阶段向前端展示的提示文案。
     */
    public static final String VALUE_PREFERENCE = "Luna 正在记录主人的偏好...";
    /**
     * 记忆处理阶段向前端展示的提示文案。
     */
    public static final String VALUE_MEMORY = "Luna 正在回忆过去的点点滴滴...";
    /**
     * 日程处理阶段向前端展示的提示文案。
     */
    public static final String VALUE_SCHEDULE = "Luna 正在安排日程任务...";
    /**
     * 网页搜索阶段向前端展示的提示文案。
     */
    public static final String VALUE_SEARCH_WEB = "Luna 正在全网搜索最新资讯...";
    /**
     * 图片搜索阶段向前端展示的提示文案。
     */
    public static final String VALUE_SEARCH_IMAGES = "Luna 正在搜索相关图片...";
    /**
     * 新闻搜索阶段向前端展示的提示文案。
     */
    public static final String VALUE_SEARCH_NEWS = "Luna 正在查阅最新新闻...";
    /**
     * 以图搜图阶段向前端展示的提示文案。
     */
    public static final String VALUE_SEARCH_LENS = "Luna 正在进行以图搜图...";
    /**
     * 网页抓取阶段向前端展示的提示文案。
     */
    public static final String VALUE_SCRAPE_WEB = "Luna 正在抓取网页内容...";
    /**
     * 知识库处理阶段向前端展示的提示文案。
     */
    public static final String VALUE_KNOWLEDGE_BASE = "Luna 正在查阅或整理本地知识库...";
    /**
     * 日志处理阶段向前端展示的提示文案。
     */
    public static final String VALUE_LOG = "Luna 正在查阅系统日志...";
    /**
     * 思考阶段向前端展示的提示文案。
     */
    public static final String VALUE_THINKING = "Luna 正在思考...";
    /**
     * 语言组织阶段向前端展示的提示文案。
     */
    public static final String VALUE_THINKING_ORGANIZE = "Luna 正在组织语言...";
    /**
     * 空闲阶段展示的状态文案，留空表示不额外提示。
     */
    public static final String VALUE_IDLE = "";
    /**
     * 检索阶段向前端展示的提示文案。
     */
    public static final String VALUE_RETRIEVING = "Luna 正在翻阅本地记忆与知识库...";
    /**
     * 启动阶段向前端展示的提示文案。
     */
    public static final String VALUE_STARTING = "Luna 正在苏醒...";

    /**
     * 计划编排阶段向前端展示的提示文案。
     */
    public static final String VALUE_PLAN = "Luna 正在执行编排计划...";
    /**
     * 代码工程阶段向前端展示的提示文案。
     */
    public static final String VALUE_CODEOPS = "Luna 正在处理代码工程任务...";
    /**
     * 桌面能力阶段向前端展示的提示文案。
     */
    public static final String VALUE_DESKTOP = "Luna 正在执行桌面能力任务...";
    /**
     * 执行锁阶段向前端展示的提示文案。
     */
    public static final String VALUE_LOCK = "Luna 正在处理执行锁...";
    /**
     * 报告生成阶段向前端展示的提示文案。
     */
    public static final String VALUE_REPORT = "Luna 正在生成任务报告...";
}
