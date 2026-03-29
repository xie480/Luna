package org.yilena.luna.rag.models;

import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Value
@Builder
/**
 * RAG 统一响应模型。
 */
public class RetrievalResponse {
    // 最终命中的路由
    RetrievalRoute route;
    // 改写后的查询
    String rewrittenQuery;

    // 按 source 分组的证据
    @Builder.Default
    Map<RetrievalSource, List<Evidence>> evidences = Collections.emptyMap();

    // 元信息（耗时、queryType、sourcesUsed 等）
    @Builder.Default
    Map<String, Object> meta = Collections.emptyMap();
}
