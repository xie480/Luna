package org.yilena.luna.adapter.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.yilena.luna.adapter.LlmAdapter;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.List;

/**
 * 真實模型適配器
 * 封裝 LlmClientUtil
 */
@Primary
@Service("realLlmAdapter")
@RequiredArgsConstructor
public class RealLlmAdapter implements LlmAdapter {

    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;

    @Override
    public String generate(String prompt) {
        LlmRequest request = LlmRequest.builder()
                .modelType(ModelType.OPENAI_COMPATIBLE)
                .modelName(geminiProperty.getMid().getModelName())
                .messages(List.of(LlmMessage.user(prompt)))
                .temperature(0.2) // 任務型調用溫度低一點
                .enablePromptInjectionCheck(false) // 内部 Agent/任务调用，不做用户注入检测
                .build();

        LlmResponse response = llmClientUtil.generate(request);
        return response != null ? response.getContent() : null;
    }
}
