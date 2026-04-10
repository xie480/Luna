package org.yilena.luna.adapter.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.adapter.LlmAdapter;

/**
 * Mock 大模型适配器，用于本地调试和联调场景下模拟模型返回结果，
 * 避免开发环境强依赖真实模型服务。
 */
@Slf4j
@Service("mockLlmAdapter")
public class MockLlmAdapter implements LlmAdapter {

    @Override
    public String generate(String prompt) {
        /**
         * 先记录收到的提示词内容，
         * 便于本地联调时确认当前链路发给模型的输入。
         */
        log.info("MockLlmAdapter received prompt: {}", prompt);

        /**
         * 当提示词明显用于工具决策时，返回固定工具名，
         * 方便验证工具路由链路是否通畅。
         */
        if (prompt != null && (prompt.contains("决策") || prompt.toLowerCase().contains("decision"))) {
            return "{\"tool_name\": \"web_search\"}";
        }

        /**
         * 当提示词偏向参数生成时，返回一份固定参数 JSON，
         * 便于验证参数填充和后续调用流程。
         */
        if (prompt != null && (prompt.contains("args") || prompt.contains("参数"))) {
            return "{\"query\": \"Luna v2.0 架构设计\"}";
        }

        /**
         * 其他场景统一返回固定回复，
         * 确保离线模式下对话链路也能继续运行。
         */
        return "{\"emotion\":\"Smile\",\"reply\":\"这是 Mock 模型的默认回复。\"}";
    }
}
