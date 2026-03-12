package org.yilena.luna.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一的 LLM 消息体，支持多模态（文本+图片）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmMessage {
    
    /**
     * 角色：system, user, assistant
     */
    private String role;
    
    /**
     * 文本内容
     */
    private String text;
    
    /**
     * 图片列表（支持 URL 或 Base64 编码）
     * 用于多模态视觉模型
     */
    private List<String> imageUrls;
    
    public static LlmMessage user(String text) {
        return LlmMessage.builder().role("user").text(text).build();
    }
    
    public static LlmMessage system(String text) {
        return LlmMessage.builder().role("system").text(text).build();
    }
    
    public static LlmMessage assistant(String text) {
        return LlmMessage.builder().role("assistant").text(text).build();
    }
}
