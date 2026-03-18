package org.yilena.luna.router;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.service.McpService;

import java.util.List;

/**
 * 工具路由
 * 負責根據用戶輸入檢索候選工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRouter {

    private final McpService mcpService;

    /**
     * 查找候選工具
     * @param query 用戶查詢語句
     * @return 候選資源列表
     */
    public List<Resource> findCandidates(String query) {
        // 簡化版實現：直接返回所有工具，或者根據關鍵詞模糊匹配
        // 生產環境應對接向量數據庫進行語義檢索
        log.info("正在為 Query [{}] 檢索工具...", query);
        
        // 使用 McpService 統一檢索 Tool 和 Skill
        List<Resource> candidates = mcpService.searchResources(query);
        
        // 如果工具很多，這裡必須加過濾邏輯 (例如只取 Top 10)
        if (candidates.size() > 10) {
            return candidates.subList(0, 10);
        }
        return candidates;
    }
}
