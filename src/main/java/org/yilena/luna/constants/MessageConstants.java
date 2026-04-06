package org.yilena.luna.constants;

/**
 * Shared user-facing and business messages.
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
