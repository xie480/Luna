package org.yilena.luna.exception.impl;

import lombok.Getter;
import org.yilena.luna.entity.ApprovalTask;

/**
 * 需要審批異常
 * 當工具執行被 ExecutionGate 攔截時拋出此異常，用於中斷當前線程
 */
@Getter
public class NeedApprovalException extends RuntimeException {
    
    private final ApprovalTask approvalTask; // 声明成员字段

    public NeedApprovalException(ApprovalTask approvalTask) { // 定义方法签名
        super("操作需要審批: " + approvalTask.getTaskId()); // 执行语句逻辑
        this.approvalTask = approvalTask; // 执行赋值操作
    } // 结束当前代码块
} // 结束当前代码块
