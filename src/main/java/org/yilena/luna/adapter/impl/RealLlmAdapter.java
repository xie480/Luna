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

    private final LlmClientUtil llmClientUtil; // 声明成员字段
    private final GeminiProperty geminiProperty; // 声明成员字段

    @Override // 声明注解
    public String generate(String prompt) { // 定义方法签名
        LlmRequest request = LlmRequest.builder() // 执行赋值操作
                .modelType(ModelType.OPENAI_COMPATIBLE) // 执行当前逻辑
                .modelName(geminiProperty.getMid().getModelName()) // 执行当前逻辑
                .messages(List.of(LlmMessage.user(prompt))) // 执行当前逻辑
                .temperature(0.2) // 任務型調用溫度低一點
                .enablePromptInjectionCheck(false) // 内部 Agent/任务调用，不做用户注入检测
                .build(); // 执行语句逻辑

        LlmResponse response = llmClientUtil.generate(request); // 执行赋值操作
        return response != null ? response.getContent() : null; // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
