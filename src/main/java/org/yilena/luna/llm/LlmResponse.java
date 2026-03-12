package org.yilena.luna.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一的 LLM 响应结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {
    
    /**
     * 模型生成的文本回复
     */
    private String content;
}
