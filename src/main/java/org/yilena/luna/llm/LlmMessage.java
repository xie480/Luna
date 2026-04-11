package org.yilena.luna.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 消息模型，负责描述一次对话中的角色内容和多模态输入片段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmMessage {

    /**
     * 消息角色，如 system、user、assistant。
     */
    private String role;

    /**
     * 消息中的文本内容。
     */
    private String text;

    /**
     * 附带的图片地址列表，可用于多模态模型输入。
     */
    private List<String> imageUrls;

    /**
     * 快速构造用户消息。
     */
    public static LlmMessage user(String text) {
        return LlmMessage.builder().role("user").text(text).build();
    }

    /**
     * 快速构造系统消息。
     */
    public static LlmMessage system(String text) {
        return LlmMessage.builder().role("system").text(text).build();
    }

    /**
     * 快速构造助手消息。
     */
    public static LlmMessage assistant(String text) {
        return LlmMessage.builder().role("assistant").text(text).build();
    }
}
