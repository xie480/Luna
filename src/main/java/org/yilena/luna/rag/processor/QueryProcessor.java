package org.yilena.luna.rag.processor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.adapters.EmbeddingProvider;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.planner.ModelDrivenRagPlanner;

import java.util.HashMap;
import java.util.Map;

/** Query pre-processor with model-first planning and heuristic fallback. */
@Component
@RequiredArgsConstructor
public class QueryProcessor {

    private final EmbeddingProvider embeddingProvider;
    private final RagProperties ragProperties;
    private final ModelDrivenRagPlanner modelDrivenRagPlanner;

    public QueryObject process(RetrievalRequest request) {
        String original = request.getQuery() == null ? "" : request.getQuery();
        String normalized = normalize(original);
        ModelDrivenRagPlanner.QueryPlanDecision planDecision = modelDrivenRagPlanner.planQuery(original, normalized, request);
        String queryType = planDecision.getQueryType() == null ? detectQueryType(normalized) : planDecision.getQueryType();
        String rewritten = planDecision.getRewrittenQuery() == null
                ? rewrite(normalized, queryType)
                : planDecision.getRewrittenQuery();
        String embedding = embeddingProvider.embedding(rewritten);

        Map<String, Object> filters = new HashMap<>();
        filters.put("query_type", queryType);
        if (planDecision.getRouteHint() != null) {
            filters.put("route_hint", planDecision.getRouteHint().value());
        }
        filters.put("query_complexity", planDecision.getComplexity());

        return QueryObject.builder()
                .originalQuery(original)
                .normalizedQuery(normalized)
                .rewrittenQuery(rewritten)
                .sessionId(request.getSessionId())
                .conversationContext(request.getConversationContext())
                .queryType(queryType)
                .possibleFilters(filters)
                .embedding(embedding)
                .build();
    }

    private String normalize(String query) {
        return query == null ? "" : query.trim().replaceAll("\\s+", " ");
    }

    private String detectQueryType(String query) {
        if (containsAny(query, ragProperties.getPreciseKeywords())) {
            return "precise_lookup";
        }
        if (containsAny(query, ragProperties.getAnalysisKeywords())) {
            return "analysis_reasoning";
        }
        if (query.contains("结合") || query.contains("根据") || query.contains("偏好")) {
            return "multi_source_reasoning";
        }
        return "general_retrieval";
    }

    private String rewrite(String normalized, String queryType) {
        if ("analysis_reasoning".equals(queryType)) {
            return "请围绕问题进行结构化检索与分析：" + normalized;
        }
        return normalized;
    }

    private boolean containsAny(String query, java.util.List<String> keywords) {
        if (query == null || query.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        return keywords.stream().anyMatch(query::contains);
    }
}
