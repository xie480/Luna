package org.yilena.luna.adapter.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.adapter.LlmAdapter;

/**
 * Mock 大模型适配器，用于本地调试和联调场景下模拟模型返回结果，避免依赖外部模型服务。
 */
@Slf4j
@Service("mockLlmAdapter")
public class MockLlmAdapter implements LlmAdapter {

    @Override
    public String generate(String prompt) {
        /**
         * 先记录收到的 Prompt，便于本地联调时确认当前链路发送给模型的输入内容。
         */
        log.info("MockLlmAdapter received prompt: {}", prompt);

        /**
         * 当 Prompt 明显是在做工具决策时，返回一个固定工具名，帮助验证工具路由链路。
         */
        if (prompt.contains("决策")) {
            return "{\"tool_name\": \"web_search\"}";
        }

        /**
         * 当 Prompt 侧重参数生成时，返回一份固定参数 JSON，便于验证参数填充流程。
         */
        if (prompt.contains("args") || prompt.contains("参数")) {
            return "{\"query\": \"Luna v2.0 架构设计\"}";
        }

        /**
         * 其余场景回退到固定回复，保证对话链路在离线模式下也能走通。
         */
        return "{\"emotion\":\"Smile\",\"reply\":\"这是 Mock 模型的默认回复。\"}";
    }
}
