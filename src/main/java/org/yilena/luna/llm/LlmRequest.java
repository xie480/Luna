package org.yilena.luna.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.ModelType;

import java.util.List;

/**
 * 统一的 LLM 请求参数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {
    
    /**
     * 指定使用的模型类型
     */
    private ModelType modelType;
    
    /**
     * 具体的模型版本名称
     */
    private String modelName;
    
    /**
     * 上下文消息列表
     */
    private List<LlmMessage> messages;
    
    /**
     * 创造力/温度值
     */
    @Builder.Default
    private Double temperature = 0.7;
}
