package org.yilena.luna.rag.models;

import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 该模型用于定义统一的 RAG 检索响应，输出命中的路由、改写查询、证据分组和调试元数据。
 */
@Value
@Builder
public class RetrievalResponse {
    /**
     * 最终命中的检索路由。
     */
    RetrievalRoute route;
    /**
     * 改写后的查询文本。
     */
    String rewrittenQuery;
    /**
     * 按数据源分组的证据结果。
     */
    @Builder.Default
    Map<RetrievalSource, List<Evidence>> evidences = Collections.emptyMap();
    /**
     * 按证据语义角色分组的结果。
     */
    @Builder.Default
    Map<EvidenceRole, List<Evidence>> evidenceRoleGroups = Collections.emptyMap();
    /**
     * 检索过程产生的元信息，例如耗时、queryType 或使用的数据源。
     */
    @Builder.Default
    Map<String, Object> meta = Collections.emptyMap();
}
