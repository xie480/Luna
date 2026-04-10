package org.yilena.luna.exception.impl;

import lombok.Getter;
import org.yilena.luna.entity.ApprovalTask;

/**
 * 需要审批异常，用于在工具执行被审批门禁拦截时中断当前流程，
 * 并将待审批任务抛给上层统一处理。
 */
@Getter
public class NeedApprovalException extends RuntimeException {

    /**
     * 当前等待审批的任务信息。
     */
    private final ApprovalTask approvalTask;

    /**
     * 使用待审批任务构造异常，并附带可读的审批提示信息。
     */
    public NeedApprovalException(ApprovalTask approvalTask) {
        super("操作需要审批: " + approvalTask.getTaskId());
        this.approvalTask = approvalTask;
    }
}
