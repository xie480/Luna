package org.yilena.luna.rag.adapters;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.utils.LlmClientUtil;

/**
 * 该适配器基于统一的 LLM 客户端生成 Embedding，并在失败时返回空值交给上层决定降级策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmEmbeddingProvider implements EmbeddingProvider {

    /**
     * 统一 LLM 调用工具。
     */
    private final LlmClientUtil llmClientUtil;

    /**
     * 调用底层 LLM 能力生成文本向量，异常时只记录告警，避免直接阻断检索主链路。
     */
    @Override
    public String embedding(String text) {
        try {
            return llmClientUtil.getEmbedding(text);
        } catch (Exception e) {
            log.warn("RAG embedding 生成失败: {}", e.getMessage());
            return null;
        }
    }
}
