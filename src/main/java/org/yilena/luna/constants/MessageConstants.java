package org.yilena.luna.constants;

/**
 * 通用消息常量类，负责统一维护面向用户和业务流程的常用提示文案，
 * 避免各类接口和过滤器重复硬编码提示内容。
 */
public final class MessageConstants {

    private MessageConstants() {
    }

    public static final String LOGOUT_SUCCESS = "已登出";
    public static final String APPROVAL_PARAMS_REQUIRED = "taskId and approved are required";
    public static final String APPROVAL_REQUIRED = "操作需要审批，请在前端确认";
    public static final String UNAUTHORIZED_DEFAULT = "未授权，请先登录";
    public static final String UNKNOWN_INPUT = "无法获取";
    public static final String REQUEST_BODY_PARSE_FAILED = "解析失败";
    public static final String REQUEST_BODY_EMPTY = "Body为空或未被读取";
}
