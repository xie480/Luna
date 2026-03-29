package org.yilena.luna.rag.models;

import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.Map;

@Value
@Builder
/**
 * 路由决策结果对象。
 */
public class RoutePlan {
    // 命中路由
    RetrievalRoute route;
    // 是否需要 query rewrite
    boolean needsRewrite;
    // 是否需要 rerank
    boolean needsRerank;
    // query 类型标签
    String queryType;

    // 各 source 的 top-k 配置
    @Builder.Default
    Map<RetrievalSource, Integer> topKConfig = Collections.emptyMap();
}
