package org.yilena.luna.exception.impl;

import lombok.Getter;
import org.yilena.luna.entity.ApprovalTask;

/**
 * 需要審批異常
 * 當工具執行被 ExecutionGate 攔截時拋出此異常，用於中斷當前線程
 */
@Getter
public class NeedApprovalException extends RuntimeException {
    
    private final ApprovalTask approvalTask;

    public NeedApprovalException(ApprovalTask approvalTask) {
        super("操作需要審批: " + approvalTask.getTaskId());
        this.approvalTask = approvalTask;
    }
}
