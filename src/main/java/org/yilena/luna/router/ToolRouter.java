package org.yilena.luna.router;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.mapper.ResourceMapper;

import java.util.List;

/**
 * 工具路由
 * 負責根據用戶輸入檢索候選工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRouter {

    private final ResourceMapper resourceMapper;

    /**
     * 查找候選工具
     * @param query 用戶查詢語句
     * @return 候選資源列表
     */
    public List<Resource> findCandidates(String query) {
        // 簡化版實現：直接返回所有工具，或者根據關鍵詞模糊匹配
        // 生產環境應對接向量數據庫進行語義檢索
        log.info("正在為 Query [{}] 檢索工具...", query);
        
        LambdaQueryWrapper<Resource> wrapper = new LambdaQueryWrapper<>();
        // 這裡簡單地返回所有工具，讓 LLM 自己挑選
        // 如果工具很多，這裡必須加過濾邏輯
        wrapper.last("LIMIT 10"); 
        
        return resourceMapper.selectList(wrapper);
    }
}
