package org.yilena.luna.rag.models;

import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;

@Value
@Builder
/**
 * RAG 统一请求模型。
 */
public class RetrievalRequest {
    // 原始查询
    String query;
    // 当前会话标识（通常使用 JWT jti）
    String sessionId;

    // 会话上下文（当前版本预留）
    @Builder.Default
    List<String> conversationContext = Collections.emptyList();

    // 允许的路由范围
    @Builder.Default
    List<RetrievalRoute> allowedRoutes = RetrievalRoute.all();

    // 允许的数据源范围
    @Builder.Default
    List<RetrievalSource> sourceScope = RetrievalSource.all();

    // 额外选项（debug/超时等）
    @Builder.Default
    RetrievalOptions options = RetrievalOptions.builder().build();
}
