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
 * 真实大模型适配器，负责将统一的文本生成请求转换为实际模型调用，
 * 并返回原始模型输出结果。
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
         * 先组装统一的大模型请求对象，固定模型类型、温度和消息结构，
         * 保证上层不同调用场景都走同一套调用规范。
         */
        LlmRequest request = LlmRequest.builder()
                .modelType(ModelType.OPENAI_COMPATIBLE)
                .modelName(geminiProperty.getMid().getModelName())
                .messages(List.of(LlmMessage.user(prompt)))
                .temperature(LlmConstant.TASK_TEMPERATURE)
                .enablePromptInjectionCheck(false)
                .build();

        /**
         * 调用底层模型客户端并提取文本结果，
         * 作为上层链路可直接消费的生成内容。
         */
        LlmResponse response = llmClientUtil.generate(request);
        return response != null ? response.getContent() : null;
    }
}
