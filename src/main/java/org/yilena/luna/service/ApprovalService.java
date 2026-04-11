package org.yilena.luna.service;

import org.yilena.luna.entity.Resource;

/**
 * 审批服务接口，负责创建人工审批任务并处理审批回调结果。
 * 该接口处于敏感工具调用链路中，用于串联中断、确认和恢复执行流程。
 */
public interface ApprovalService {

    /**
     * 创建审批任务并中断当前执行流程。
     * @param sessionId 会话 ID
     * @param resource 需要审批的目标资源
     * @param argsJson 工具调用参数的 JSON 字符串
     */
    void createTaskAndInterrupt(String sessionId, Resource resource, String argsJson);

    /**
     * 处理用户提交的审批结果并返回后续执行结果。
     * @param taskId 审批任务 ID
     * @param approved 是否批准当前操作
     * @return 审批处理后的执行结果 JSON
     */
    String processApproval(String taskId, boolean approved);
}
