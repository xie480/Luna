package org.yilena.luna.rag.models;

import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 该模型用于承载路由决策结果，描述命中的检索路线、数据源组合和后续处理策略。
 */
@Value
@Builder
public class RoutePlan {
    /**
     * 命中的检索路由。
     */
    RetrievalRoute route;
    /**
     * 命中的数据源集合。
     */
    @Builder.Default
    List<RetrievalSource> sources = Collections.emptyList();
    /**
     * 是否需要对查询做改写。
     */
    boolean needsRewrite;
    /**
     * 是否需要对证据结果做重排序。
     */
    boolean needsRerank;
    /**
     * 当前查询类型标签。
     */
    String queryType;
    /**
     * 按数据源定义的 topK 配置。
     */
    @Builder.Default
    Map<RetrievalSource, Integer> topKConfig = Collections.emptyMap();
}
