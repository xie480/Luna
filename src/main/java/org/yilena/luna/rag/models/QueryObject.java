package org.yilena.luna.rag.models;

import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 该模型用于承载查询处理后的中间结果，在 processor、router、pipeline 和 retriever 之间传递统一上下文。
 */
@Value
@Builder(toBuilder = true)
public class QueryObject {
    /**
     * 原始用户输入。
     */
    String originalQuery;
    /**
     * 归一化后的查询文本。
     */
    String normalizedQuery;
    /**
     * 更适合检索的改写查询。
     */
    String rewrittenQuery;
    /**
     * 当前会话标识。
     */
    String sessionId;
    /**
     * 查询文本对应的 embedding 向量。
     */
    List<Double> embedding;
    /**
     * 查询类型标签，例如 precise_lookup、analysis_reasoning。
     */
    String queryType;
    /**
     * 查询标签集合，例如时效、偏好或精确查找等信号。
     */
    @Builder.Default
    List<String> queryTags = Collections.emptyList();
    /**
     * 对话上下文消息列表。
     */
    @Builder.Default
    List<ConversationMessage> conversationContext = Collections.emptyList();
    /**
     * 检索阶段可能使用的过滤条件。
     */
    @Builder.Default
    Map<String, Object> possibleFilters = Collections.emptyMap();
}
