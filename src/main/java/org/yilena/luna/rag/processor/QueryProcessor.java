package org.yilena.luna.rag.processor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.adapters.EmbeddingProvider;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.models.ConversationMessage;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.planner.ModelDrivenRagPlanner;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class QueryProcessor {

    private final EmbeddingProvider embeddingProvider;
    private final RagProperties ragProperties;
    private final ModelDrivenRagPlanner modelDrivenRagPlanner;

    public QueryObject process(RetrievalRequest request) {
        String original = request.getQuery() == null ? "" : request.getQuery();
        String normalized = normalize(original);
        String contextResolved = resolveReferences(normalized, request.getConversationContext());
        ModelDrivenRagPlanner.QueryPlanDecision planDecision = modelDrivenRagPlanner.planQuery(original, contextResolved, request);

        String queryType = planDecision.getQueryType() == null
                ? detectQueryType(contextResolved)
                : planDecision.getQueryType();
        String rewritten = planDecision.getRewrittenQuery() == null
                ? rewrite(contextResolved, queryType)
                : planDecision.getRewrittenQuery();

        List<Double> embedding = parseEmbedding(embeddingProvider.embedding(rewritten));
        List<String> queryTags = detectQueryTags(contextResolved, queryType);

        Map<String, Object> filters = new HashMap<>();
        filters.put("query_type", queryType);
        if (planDecision.getRouteHint() != null) {
            filters.put("route_hint", planDecision.getRouteHint().value());
        }
        filters.put("query_complexity", planDecision.getComplexity());
        filters.put("query_tags", queryTags);

        if (containsAny(contextResolved, ragProperties.getRecencyKeywords())) {
            filters.put("time_window_days", 30);
        }

        String prefKey = detectPreferenceKey(contextResolved);
        if (prefKey != null) {
            filters.put("pref_key", prefKey);
        }

        if (!contextResolved.equals(normalized)) {
            filters.put("coref_resolved", true);
        }

        List<RetrievalSource> inferredSources = inferSources(contextResolved, request.getSourceScope());
        filters.put("inferred_sources", inferredSources.stream().map(RetrievalSource::value).collect(Collectors.toList()));
        filters.put("source_count", inferredSources.size());

        List<Integer> sourceTypes = detectKnowledgeSourceTypes(contextResolved);
        if (!sourceTypes.isEmpty()) {
            filters.put("knowledge_source_types", sourceTypes);
        }
        List<String> memoryTypes = detectMemoryTypes(contextResolved);
        if (!memoryTypes.isEmpty()) {
            filters.put("memory_types", memoryTypes);
            if (memoryTypes.size() == 1) {
                filters.put("memory_type", memoryTypes.get(0));
            }
        }

        return QueryObject.builder()
                .originalQuery(original)
                .normalizedQuery(contextResolved)
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
        if (containsAny(query, ragProperties.getMultiSourceKeywords())) {
            return "multi_source_reasoning";
        }
        return "general_retrieval";
    }

    private String rewrite(String normalized, String queryType) {
        return ragProperties.rewriteWithTemplate(queryType, normalizeText(normalized));
    }

    private boolean containsAny(String query, List<String> keywords) {
        if (query == null || query.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String normalizedQuery = normalizeText(query);
        return keywords.stream()
                .map(this::normalizeText)
                .filter(keyword -> !keyword.isBlank())
                .anyMatch(normalizedQuery::contains);
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

        if (containsAny(query, ragProperties.getRecencyKeywords())) {
            tags.add("needs_recency");
        }

        if (containsAny(query, ragProperties.keywordsOf(RetrievalSource.PREFERENCE))) {
            tags.add("preference_lookup");
        }

        if (containsAny(query, ragProperties.keywordsOf(RetrievalSource.MEMORY))) {
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
        String normalizedQuery = normalizeText(query);
        Map<String, String> keyMap = ragProperties.getPreferenceKeyAliases();
        if (keyMap == null || keyMap.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : keyMap.entrySet()) {
            String keyAlias = normalizeText(entry.getKey());
            if (!keyAlias.isBlank() && normalizedQuery.contains(keyAlias)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String resolveReferences(String normalized, List<ConversationMessage> context) {
        if (normalized == null || normalized.isBlank()) {
            return "";
        }
        if (context == null || context.isEmpty()) {
            return normalized;
        }
        if (!containsAny(normalized, ragProperties.getReferenceKeywords())) {
            return normalized;
        }

        String anchor = latestUserTopic(context, normalized);
        if (anchor == null || anchor.isBlank()) {
            return normalized;
        }
        return normalized + "（指代补全：" + trim(anchor, 80) + "）";
    }

    private String latestUserTopic(List<ConversationMessage> context, String currentQuery) {
        for (int i = context.size() - 1; i >= 0; i--) {
            ConversationMessage turn = context.get(i);
            if (turn == null || turn.getContent() == null || turn.getContent().isBlank()) {
                continue;
            }
            String role = turn.getRole() == null ? "" : turn.getRole().trim().toLowerCase();
            if (!"user".equals(role)) {
                continue;
            }
            String content = normalize(turn.getContent());
            if (content.isBlank() || content.equals(currentQuery)) {
                continue;
            }
            return content;
        }

        for (int i = context.size() - 1; i >= 0; i--) {
            ConversationMessage turn = context.get(i);
            if (turn == null || turn.getContent() == null || turn.getContent().isBlank()) {
                continue;
            }
            return normalize(turn.getContent());
        }

        return null;
    }

    private String trim(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace("\uFEFF", "")
                .replace("\uFFFD", "")
                .trim();
    }

    private List<RetrievalSource> inferSources(String query, List<RetrievalSource> scoped) {
        List<RetrievalSource> scope = (scoped == null || scoped.isEmpty()) ? RetrievalSource.all() : scoped;
        Set<RetrievalSource> inferred = new LinkedHashSet<>();

        for (Map.Entry<RetrievalSource, List<String>> entry : ragProperties.sourceKeywordMap().entrySet()) {
            if (containsAny(query, entry.getValue())) {
                inferred.add(entry.getKey());
            }
        }

        if (containsAny(query, ragProperties.getMultiSourceKeywords())) {
            inferred.addAll(scope);
        }

        List<RetrievalSource> routed = inferred.stream().filter(scope::contains).toList();
        if (!routed.isEmpty()) {
            return routed;
        }

        if (scope.contains(RetrievalSource.KNOWLEDGE)) {
            return List.of(RetrievalSource.KNOWLEDGE);
        }
        return List.of(scope.get(0));
    }

    private List<Integer> detectKnowledgeSourceTypes(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Map<Integer, List<String>> map = ragProperties.getKnowledgeSourceTypeKeywords();
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        Set<Integer> sourceTypes = new LinkedHashSet<>();
        for (Map.Entry<Integer, List<String>> entry : map.entrySet()) {
            if (containsAny(query, entry.getValue())) {
                sourceTypes.add(entry.getKey());
            }
        }
        return sourceTypes.stream().filter(Objects::nonNull).collect(Collectors.toCollection(ArrayList::new));
    }

    private List<String> detectMemoryTypes(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Map<String, List<String>> map = ragProperties.getMemoryTypeKeywords();
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        Set<String> memoryTypes = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            if (!containsAny(query, entry.getValue())) {
                continue;
            }
            for (String variant : expandMemoryTypeVariants(entry.getKey())) {
                String normalized = normalizeText(variant);
                if (!normalized.isBlank()) {
                    memoryTypes.add(normalized);
                }
            }
        }
        return new ArrayList<>(memoryTypes);
    }

    private List<String> expandMemoryTypeVariants(String memoryType) {
        String normalized = normalizeText(memoryType).toUpperCase();
        return switch (normalized) {
            case "0", "FACT", "DOMAIN_FACT" -> List.of("0", "FACT", "DOMAIN_FACT");
            case "1", "PREFERENCE" -> List.of("1", "PREFERENCE");
            case "2", "SUMMARY" -> List.of("2", "SUMMARY");
            case "3", "REFLECTION" -> List.of("3", "REFLECTION");
            case "DECISION" -> List.of("DECISION");
            case "SUCCESS" -> List.of("SUCCESS");
            case "FAILURE" -> List.of("FAILURE");
            case "PARTIAL" -> List.of("PARTIAL");
            case "RULE" -> List.of("RULE");
            default -> List.of(normalized);
        };
    }
}
