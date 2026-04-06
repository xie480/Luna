package org.yilena.luna.constants;

/**
 * Approval flow literals.
 */
public final class ApprovalConstants {

    private ApprovalConstants() {
    }

    public static final String REDIS_PREFIX = "luna:approval:";
    public static final long EXPIRE_MINUTES = 10L;

    public static final String EVENT_APPROVAL_REQUEST = "APPROVAL_REQUEST";
    public static final String EVENT_APPROVAL_RESULT = "APPROVAL_RESULT";

    public static final String ERROR_TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED";
    public static final String ERROR_USER_REJECTED = "USER_REJECTED";
    public static final String ERROR_APPROVAL_TOOL_NAME_MISSING = "APPROVAL_TOOL_NAME_MISSING";
    public static final String ERROR_TOOL_EXECUTION_NULL = "TOOL_EXECUTION_NULL";

    public static final String MESSAGE_APPROVAL_REQUIRED = "Approval required.";
    public static final String MESSAGE_APPROVAL_TASK_NOT_FOUND = "approval task not found or expired";
    public static final String MESSAGE_RUNNING_APPROVED_TOOL = "Running approved tool...";
    public static final String MESSAGE_REJECTED_CONTINUE = "Approval rejected, continue without tool.";
    public static final String MESSAGE_MISSING_TOOL_NAME = "approval task missing tool name";
    public static final String MESSAGE_TOOL_NULL = "tool execution returned null";
    public static final String MESSAGE_EXECUTE_TOOL_FAILED_PREFIX = "approval execute tool failed: ";
    public static final String MESSAGE_USER_DENIED_OPERATION = "User denied the operation.";
    public static final String MESSAGE_GOVERNANCE_BLOCKED = "context governance blocked: final governed workset is empty";
}
