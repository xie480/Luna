package org.yilena.luna.service;

import org.yilena.luna.entity.Resource;

/**
 * 審批服務接口
 */
public interface ApprovalService {

    /**
     * 創建審批任務並拋出中斷異常
     * @param sessionId 會話ID
     * @param resource 資源
     * @param argsJson 參數
     */
    void createTaskAndInterrupt(String sessionId, Resource resource, String argsJson);

    /**
     * 處理用戶審批結果
     * @param taskId 任務ID
     * @param approved 是否同意
     * @return 執行結果 JSON
     */
    String processApproval(String taskId, boolean approved);
}
