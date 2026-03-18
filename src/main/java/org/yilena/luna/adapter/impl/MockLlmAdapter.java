package org.yilena.luna.adapter.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.adapter.LlmAdapter;

/**
 * Mock 模型適配器
 * 用於本地測試，不依賴外部 API
 */
@Slf4j
@Service("mockLlmAdapter")
public class MockLlmAdapter implements LlmAdapter {

    @Override
    public String generate(String prompt) {
        log.info("MockLlmAdapter 收到 Prompt: {}", prompt);

        if (prompt.contains("决策")) {
            // 模擬決策：如果 Prompt 裡問要不要用工具，返回 web_search
            return "{\"tool_name\": \"web_search\"}";
        }
        
        if (prompt.contains("args") || prompt.contains("参数")) {
            // 模擬參數生成
            return "{\"query\": \"Luna v2.0 架构设计\"}";
        }

        // 默認回復
        return "{\"emotion\":\"Smile\",\"reply\":\"这是 Mock 模型的默认回复。\"}";
    }
}
