package org.yilena.luna.rag.models;

import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Value
@Builder(toBuilder = true)
/**
 * Query 处理后的中间对象。
 * 用于在 processor/router/pipeline/retriever 间传递统一上下文。
 */
public class QueryObject {
    // 原始用户输入
    String originalQuery;
    // 归一化查询
    String normalizedQuery;
    // 检索友好的改写查询
    String rewrittenQuery;
    // 会话 ID
    String sessionId;
    // embedding 向量
    List<Double> embedding;
    // 查询类型标签（precise_lookup / analysis_reasoning / ...）
    String queryType;
    // 查询标签（精确检索、时间窗口、偏好键命中等）
    @Builder.Default
    List<String> queryTags = Collections.emptyList();

    // 对话上下文（预留）
    @Builder.Default
    List<String> conversationContext = Collections.emptyList();

    // 检索过滤信号（预留扩展）
    @Builder.Default
    Map<String, Object> possibleFilters = Collections.emptyMap();
}
