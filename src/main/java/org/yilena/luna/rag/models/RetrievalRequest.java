package org.yilena.luna.rag.models;

import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;

/**
 * 该模型用于定义统一的 RAG 检索请求，汇总查询文本、会话上下文、可用路由和附加选项。
 */
@Value
@Builder
public class RetrievalRequest {
    /**
     * 原始查询文本。
     */
    String query;
    /**
     * 当前会话标识，通常使用 JWT 的 jti。
     */
    String sessionId;
    /**
     * 对话上下文消息列表。
     */
    @Builder.Default
    List<ConversationMessage> conversationContext = Collections.emptyList();
    /**
     * 允许参与本次检索的路由范围。
     */
    @Builder.Default
    List<RetrievalRoute> allowedRoutes = RetrievalRoute.all();
    /**
     * 允许参与本次检索的数据源范围。
     */
    @Builder.Default
    List<RetrievalSource> sourceScope = RetrievalSource.all();
    /**
     * 检索附加选项，例如调试开关和超时预算。
     */
    @Builder.Default
    RetrievalOptions options = RetrievalOptions.builder().build();
}
