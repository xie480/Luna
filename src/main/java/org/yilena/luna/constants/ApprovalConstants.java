package org.yilena.luna.constants;

/**
 * 审批流程常量类，负责统一维护审批链路中的 Redis 前缀、事件名、错误码和提示文案，
 * 供审批任务创建、执行和回调阶段复用。
 */
public final class ApprovalConstants {

    private ApprovalConstants() {
    }

    /**
     * 审批任务在 Redis 中的键前缀。
     */
    public static final String REDIS_PREFIX = "luna:approval:";
    /**
     * 审批任务默认过期时间，单位为分钟。
     */
    public static final long EXPIRE_MINUTES = 10L;

    /**
     * 审批请求事件名。
     */
    public static final String EVENT_APPROVAL_REQUEST = "APPROVAL_REQUEST";
    /**
     * 审批结果事件名。
     */
    public static final String EVENT_APPROVAL_RESULT = "APPROVAL_RESULT";

    /**
     * 工具执行失败错误码。
     */
    public static final String ERROR_TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED";
    /**
     * 用户拒绝审批错误码。
     */
    public static final String ERROR_USER_REJECTED = "USER_REJECTED";
    /**
     * 审批任务缺少工具名错误码。
     */
    public static final String ERROR_APPROVAL_TOOL_NAME_MISSING = "APPROVAL_TOOL_NAME_MISSING";
    /**
     * 工具执行返回空结果错误码。
     */
    public static final String ERROR_TOOL_EXECUTION_NULL = "TOOL_EXECUTION_NULL";

    /**
     * 需要审批时返回给前端的提示文案。
     */
    public static final String MESSAGE_APPROVAL_REQUIRED = "Approval required.";
    /**
     * 审批任务不存在或已过期时的提示文案。
     */
    public static final String MESSAGE_APPROVAL_TASK_NOT_FOUND = "approval task not found or expired";
    /**
     * 开始执行已审批工具时的提示文案。
     */
    public static final String MESSAGE_RUNNING_APPROVED_TOOL = "Running approved tool...";
    /**
     * 用户拒绝后继续无工具执行时的提示文案。
     */
    public static final String MESSAGE_REJECTED_CONTINUE = "Approval rejected, continue without tool.";
    /**
     * 审批任务缺少工具名时的提示文案。
     */
    public static final String MESSAGE_MISSING_TOOL_NAME = "approval task missing tool name";
    /**
     * 工具返回空结果时的提示文案。
     */
    public static final String MESSAGE_TOOL_NULL = "tool execution returned null";
    /**
     * 执行审批工具失败时的提示前缀。
     */
    public static final String MESSAGE_EXECUTE_TOOL_FAILED_PREFIX = "approval execute tool failed: ";
    /**
     * 用户拒绝操作时的提示文案。
     */
    public static final String MESSAGE_USER_DENIED_OPERATION = "User denied the operation.";
    /**
     * 治理后工作集为空时的阻断提示文案。
     */
    public static final String MESSAGE_GOVERNANCE_BLOCKED = "context governance blocked: final governed workset is empty";
}
