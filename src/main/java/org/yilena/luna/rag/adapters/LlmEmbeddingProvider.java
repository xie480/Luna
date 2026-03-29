package org.yilena.luna.rag.adapters;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.utils.LlmClientUtil;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * 基于 LlmClientUtil 的 embedding 实现。
 * 出错时返回 null，由上层决定降级策略。
 */
public class LlmEmbeddingProvider implements EmbeddingProvider {

    // 统一模型客户端
    private final LlmClientUtil llmClientUtil;

    @Override
    public String embedding(String text) {
        try {
            return llmClientUtil.getEmbedding(text);
        } catch (Exception e) {
            // embedding 失败只告警，不抛出，避免阻断主链路
            log.warn("RAG embedding 生成失败: {}", e.getMessage());
            return null;
        }
    }
}
