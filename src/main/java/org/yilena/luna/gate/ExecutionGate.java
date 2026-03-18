package org.yilena.luna.gate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.Resource;

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
        if ("HIGH".equalsIgnoreCase(resource.getSensitivity())) {
            log.error("拒絕執行高敏感度工具: {}", resource.getName());
            throw new RuntimeException("權限不足：拒絕執行高敏感度工具 [" + resource.getName() + "]");
        }

        // 2. 審批標記檢查 (此處僅做日誌，具體流程由 Executor 處理)
        if (Boolean.TRUE.equals(resource.getRequiresApproval())) {
            log.info("工具 [{}] 需要人工審批，將進入審批流程", resource.getName());
        }
    }
}
