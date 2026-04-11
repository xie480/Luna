package org.yilena.luna.constants;

/**
 * 通用消息常量类，负责统一维护面向用户和业务流程的常用提示文案，
 * 避免各类接口和过滤器重复硬编码提示内容。
 */
public final class MessageConstants {

    private MessageConstants() {
    }

    /**
     * 用户主动退出登录后的成功提示文案。
     */
    public static final String LOGOUT_SUCCESS = "已登出";
    /**
     * 审批回调缺少任务标识或审批结果时的参数校验提示。
     */
    public static final String APPROVAL_PARAMS_REQUIRED = "taskId and approved are required";
    /**
     * 命中审批流程时返回给前端的统一确认提示。
     */
    public static final String APPROVAL_REQUIRED = "操作需要审批，请在前端确认";
    /**
     * 未登录或凭证失效时使用的默认未授权提示。
     */
    public static final String UNAUTHORIZED_DEFAULT = "未授权，请先登录";
    /**
     * 无法从请求中提取有效输入内容时的兜底提示。
     */
    public static final String UNKNOWN_INPUT = "无法获取";
    /**
     * 请求体解析失败时返回的错误提示。
     */
    public static final String REQUEST_BODY_PARSE_FAILED = "解析失败";
    /**
     * 请求体为空或已被提前消费时的提示文案。
     */
    public static final String REQUEST_BODY_EMPTY = "Body为空或未被读取";
}
