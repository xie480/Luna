package org.yilena.luna.rag.processor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.adapters.EmbeddingProvider;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.planner.ModelDrivenRagPlanner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        List<Double> embedding = parseEmbedding(embeddingProvider.embedding(rewritten));
        List<String> queryTags = detectQueryTags(normalized, queryType);

        Map<String, Object> filters = new HashMap<>();
        filters.put("query_type", queryType);
        if (planDecision.getRouteHint() != null) {
            filters.put("route_hint", planDecision.getRouteHint().value());
        }
        filters.put("query_complexity", planDecision.getComplexity());
        filters.put("query_tags", queryTags);
        if (containsAny(normalized, List.of("最近", "这段时间", "近期", "上周", "本月"))) {
            filters.put("time_window_days", 30);
        }
        String prefKey = detectPreferenceKey(normalized);
        if (prefKey != null) {
            filters.put("pref_key", prefKey);
        }

        return QueryObject.builder()
                .originalQuery(original)
                .normalizedQuery(normalized)
                .rewrittenQuery(rewritten)
                .sessionId(request.getSessionId())
                .conversationContext(request.getConversationContext())
                .queryType(queryType)
                .queryTags(queryTags)
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

    private List<Double> parseEmbedding(String rawEmbedding) {
        if (rawEmbedding == null || rawEmbedding.isBlank()) {
            return Collections.emptyList();
        }
        String cleaned = rawEmbedding.trim();
        if (cleaned.startsWith("[")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.endsWith("]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return Arrays.stream(cleaned.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Double::parseDouble)
                    .toList();
        } catch (Exception ignore) {
            return Collections.emptyList();
        }
    }

    private List<String> detectQueryTags(String query, String queryType) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add(queryType);
        if (containsAny(query, ragProperties.getPreciseKeywords())) {
            tags.add("precise_lookup");
            tags.add("exact_match_first");
        }
        if (containsAny(query, List.of("上次", "之前", "过去", "最近", "这段时间"))) {
            tags.add("needs_recency");
        }
        if (containsAny(query, List.of("偏好", "风格", "语气", "长度"))) {
            tags.add("preference_lookup");
        }
        if (containsAny(query, List.of("记忆", "记录", "历史", "经历"))) {
            tags.add("memory_lookup");
        }
        if (containsAny(query, List.of("设置", "配置", "key", "pref_key"))) {
            tags.add("key_match_priority");
        }
        return new ArrayList<>(tags);
    }

    private String detectPreferenceKey(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        Map<String, String> keyMap = Map.of(
                "回答长度", "response_length",
                "回复长度", "response_length",
                "语气", "tone",
                "风格", "response_style",
                "称呼", "nickname"
        );
        for (Map.Entry<String, String> entry : keyMap.entrySet()) {
            if (query.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
