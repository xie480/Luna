package org.yilena.luna.gate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.Sensitivity;

/**
 * 執行網關
 * 負責權限檢查和審批標記
 */
@Slf4j
@Component
public class ExecutionGate {

    /**
     * 檢查資源是否允許執行
     * @param resource 目標資源
     * @throws RuntimeException 如果權限不足
     */
    public void check(Resource resource) {
        log.info("正在進行安全檢查: {}", resource.getName());

        // 1. 敏感度檢查
        // 【v2.1 修改】不再硬性攔截 HIGH 敏感度，而是記錄日誌，
        // 讓後續的 ReflectionToolExecutor 有機會觸發審批流程 (NeedApprovalException)
        if (Sensitivity.HIGH.equals(resource.getSensitivity())) {
            log.info("檢測到高敏感度工具: {}，後續將觸發審批流程", resource.getName());
        }

        // 2. 審批標記檢查
        if (Boolean.TRUE.equals(resource.getRequiresApproval())) {
            log.info("工具 [{}] 需要人工審批，將進入審批流程", resource.getName());
        }
    }
}
