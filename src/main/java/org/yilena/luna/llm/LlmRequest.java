package org.yilena.luna.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.constants.LlmConstant;
import org.yilena.luna.enums.ModelType;

import java.util.List;

/**
 * LLM 请求模型，负责统一封装模型选择、上下文消息和生成参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {

    /**
     * 指定调用的模型类型。
     */
    private ModelType modelType;

    /**
     * 具体模型名称或版本标识。
     */
    private String modelName;

    /**
     * 发送给模型的上下文消息列表。
     */
    private List<LlmMessage> messages;

    /**
     * 生成温度参数，用于控制回答的发散程度。
     */
    @Builder.Default
    private Double temperature = LlmConstant.DEFAULT_TEMPERATURE;

    /**
     * 是否启用 Prompt Injection 检测，默认对真实用户输入开启保护。
     */
    @Builder.Default
    private Boolean enablePromptInjectionCheck = true;
}
