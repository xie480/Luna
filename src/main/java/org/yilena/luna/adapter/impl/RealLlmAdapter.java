package org.yilena.luna.adapter.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.yilena.luna.adapter.LlmAdapter;
import org.yilena.luna.constants.LlmConstant;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.List;

/**
 * 真实大模型适配器，负责把统一的 Prompt 请求转换为实际模型调用并返回原始文本结果。
 */
@Primary
@Service("realLlmAdapter")
@RequiredArgsConstructor
public class RealLlmAdapter implements LlmAdapter {

    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;

    @Override
    public String generate(String prompt) {
        /**
         * 先组装统一模型请求，固定当前模型类型、温度和消息结构，保持调用参数一致。
         */
        LlmRequest request = LlmRequest.builder()
                .modelType(ModelType.OPENAI_COMPATIBLE)
                .modelName(geminiProperty.getMid().getModelName())
                .messages(List.of(LlmMessage.user(prompt)))
                .temperature(LlmConstant.TASK_TEMPERATURE)
                .enablePromptInjectionCheck(false)
                .build();

        /**
         * 调用底层模型客户端并提取文本内容，作为上层链路的直接生成结果。
         */
        LlmResponse response = llmClientUtil.generate(request);
        return response != null ? response.getContent() : null;
    }
}
