package org.yilena.luna.rag.router;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.models.RoutePlan;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
/**
 * 该路由选择器负责根据查询类型、来源线索和延迟预算生成检索路线与 topK 分配方案。
 */
public class RouteSelector {

    /**
     * 低延迟预算阈值，低于该值时应更保守分配检索资源。
     */
    private static final long LOW_LATENCY_BUDGET_MS = 1600;
    /**
     * 高延迟预算阈值，高于该值时可启用更充分的多源检索。
     */
    private static final long HIGH_LATENCY_BUDGET_MS = 3200;

    /**
     * RAG 检索策略配置。
     */
    private final RagProperties ragProperties;

    /**
     * 根据查询对象和请求范围生成完整路由计划，包括路线、来源、改写和 rerank 决策。
     */
    public RoutePlan selectPlan(QueryObject queryObject, RetrievalRequest request) {
        List<RetrievalRoute> allowedRoutes = request.getAllowedRoutes() == null || request.getAllowedRoutes().isEmpty()
                ? RetrievalRoute.all()
                : request.getAllowedRoutes();
        List<RetrievalSource> scopedSources = request.getSourceScope() == null || request.getSourceScope().isEmpty()
                ? RetrievalSource.all()
                : new ArrayList<>(request.getSourceScope());

        /**
         * 先推断可能命中的数据源，再根据查询特征和允许路由选择最合适的检索模式。
         */
        List<RetrievalSource> inferredSources = inferSources(queryObject, scopedSources);
        RetrievalRoute route = selectRoute(queryObject, allowedRoutes, inferredSources.size());

        return RoutePlan.builder()
                .route(route)
                .sources(inferredSources)
                .queryType(queryObject.getQueryType())
                .needsRewrite(shouldRewrite(queryObject, route))
                .needsRerank(shouldRerank(queryObject))
                .topKConfig(topKByRoute(route, queryObject, request, inferredSources))
                .build();
    }

    /**
     * 按精确查找、复杂推理、配置优先级和显式 hint 依次判断最终检索路由。
     */
    private RetrievalRoute selectRoute(QueryObject queryObject, List<RetrievalRoute> allowedRoutes, int inferredSourceCount) {
        String query = queryObject.getNormalizedQuery();
        String queryType = queryObject.getQueryType();
        List<RagProperties.RetrievalRouteRule> configuredPriority = ragProperties.getRoutePriority();
        List<RagProperties.RetrievalRouteRule> effectivePriority =
                configuredPriority == null || configuredPriority.isEmpty()
                        ? List.of(
                        RagProperties.RetrievalRouteRule.SEARCH,
                        RagProperties.RetrievalRouteRule.AGENTIC,
                        RagProperties.RetrievalRouteRule.NATIVE,
                        RagProperties.RetrievalRouteRule.MODULAR
                )
                        : configuredPriority;

        if (containsAny(query, ragProperties.getPreciseKeywords())
                && allowedRoutes.contains(RetrievalRoute.SEARCH)) {
            return RetrievalRoute.SEARCH;
        }

        if (isAgenticQuery(query, queryType)
                && allowedRoutes.contains(RetrievalRoute.AGENTIC)) {
            return RetrievalRoute.AGENTIC;
        }

        for (RagProperties.RetrievalRouteRule rule : effectivePriority) {
            if (rule == RagProperties.RetrievalRouteRule.SEARCH
                    || rule == RagProperties.RetrievalRouteRule.AGENTIC) {
                continue;
            }
            RetrievalRoute route = toRoute(rule);
            if (!allowedRoutes.contains(route)) {
                continue;
            }
            if (matchRouteRule(rule, query, queryType, inferredSourceCount)) {
                return route;
            }
        }

        RetrievalRoute hintedRoute = resolveHintedRoute(queryObject, allowedRoutes);
        if (hintedRoute != null) {
            return hintedRoute;
        }

        if (inferredSourceCount <= 1 && allowedRoutes.contains(RetrievalRoute.NATIVE)) {
            return RetrievalRoute.NATIVE;
        }
        if (allowedRoutes.contains(RetrievalRoute.MODULAR)) {
            return RetrievalRoute.MODULAR;
        }
        return allowedRoutes.get(0);
    }

    private boolean matchRouteRule(
            RagProperties.RetrievalRouteRule rule,
            String query,
            String queryType,
            int inferredSourceCount
    ) {
        return switch (rule) {
            case SEARCH -> containsAny(query, ragProperties.getPreciseKeywords());
            case AGENTIC -> isAgenticQuery(query, queryType);
            case NATIVE -> inferredSourceCount <= 1;
            case MODULAR -> inferredSourceCount > 1;
        };
    }

    private boolean isAgenticQuery(String query, String queryType) {
        if ("analysis_reasoning".equalsIgnoreCase(queryType)) {
            return true;
        }
        return containsAny(query, ragProperties.getAnalysisKeywords());
    }

    private RetrievalRoute toRoute(RagProperties.RetrievalRouteRule rule) {
        return switch (rule) {
            case SEARCH -> RetrievalRoute.SEARCH;
            case NATIVE -> RetrievalRoute.NATIVE;
            case MODULAR -> RetrievalRoute.MODULAR;
            case AGENTIC -> RetrievalRoute.AGENTIC;
        };
    }

    private RetrievalRoute resolveHintedRoute(QueryObject queryObject, List<RetrievalRoute> allowedRoutes) {
        if (queryObject.getPossibleFilters() == null) {
            return null;
        }
        Object routeHint = queryObject.getPossibleFilters().get("route_hint");
        if (!(routeHint instanceof String routeHintValue) || routeHintValue.isBlank()) {
            return null;
        }
        return RetrievalRoute.fromValue(routeHintValue)
                .filter(allowedRoutes::contains)
                .orElse(null);
    }

    private boolean shouldRewrite(QueryObject queryObject, RetrievalRoute route) {
        if (route != RetrievalRoute.MODULAR && route != RetrievalRoute.AGENTIC) {
            return false;
        }
        String queryType = queryObject.getQueryType() == null ? "" : queryObject.getQueryType();
        if ("analysis_reasoning".equals(queryType) || "multi_source_reasoning".equals(queryType)) {
            return true;
        }
        if (queryObject.getPossibleFilters() != null
                && Boolean.TRUE.equals(queryObject.getPossibleFilters().get("coref_resolved"))) {
            return true;
        }
        return containsAny(queryObject.getNormalizedQuery(), ragProperties.getRewriteKeywords());
    }

    private boolean shouldRerank(QueryObject queryObject) {
        if (queryObject.getPossibleFilters() == null) {
            return true;
        }
        Object explicit = queryObject.getPossibleFilters().get("needs_rerank");
        if (explicit instanceof Boolean bool) {
            return bool;
        }
        if (explicit instanceof String text) {
            String normalized = text.trim().toLowerCase();
            if ("true".equals(normalized) || "false".equals(normalized)) {
                return Boolean.parseBoolean(normalized);
            }
        }
        return true;
    }

    private List<RetrievalSource> inferSources(QueryObject queryObject, List<RetrievalSource> scopedSources) {
        if (queryObject.getPossibleFilters() != null) {
            Object raw = queryObject.getPossibleFilters().get("inferred_sources");
            if (raw instanceof List<?> items) {
                List<RetrievalSource> parsed = items.stream()
                        .map(String::valueOf)
                        .map(RetrievalSource::fromValue)
                        .filter(java.util.Optional::isPresent)
                        .map(java.util.Optional::get)
                        .filter(scopedSources::contains)
                        .distinct()
                        .toList();
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }
        }

        String query = queryObject.getNormalizedQuery() == null ? "" : queryObject.getNormalizedQuery();
        Set<RetrievalSource> inferred = new LinkedHashSet<>();
        for (Map.Entry<RetrievalSource, List<String>> entry : ragProperties.sourceKeywordMap().entrySet()) {
            if (containsAny(query, entry.getValue())) {
                inferred.add(entry.getKey());
            }
        }

        if (containsAny(query, ragProperties.getMultiSourceKeywords())) {
            inferred.addAll(scopedSources);
        }

        List<RetrievalSource> routed = inferred.stream().filter(scopedSources::contains).collect(Collectors.toList());
        if (!routed.isEmpty()) {
            return routed;
        }
        if (scopedSources.contains(RetrievalSource.KNOWLEDGE)) {
            return List.of(RetrievalSource.KNOWLEDGE);
        }
        return List.of(scopedSources.get(0));
    }

    private Map<RetrievalSource, Integer> topKByRoute(
            RetrievalRoute route,
            QueryObject queryObject,
            RetrievalRequest request,
            List<RetrievalSource> inferredSources
    ) {
        return switch (route) {
            case SEARCH -> ragProperties.getSearchTopK();
            case NATIVE -> ragProperties.getNativeTopK();
            case MODULAR -> resolveDynamicModularTopK(queryObject, request, inferredSources);
            case AGENTIC -> ragProperties.getAgenticTopK();
        };
    }

    /**
     * docs/rag.md 8.3.4: modular top-k 在区间内动态分配。
     */
    /**
     * 为 modular 路由动态分配 topK，兼顾最小保障、延迟预算和查询复杂度。
     */
    private Map<RetrievalSource, Integer> resolveDynamicModularTopK(
            QueryObject queryObject,
            RetrievalRequest request,
            List<RetrievalSource> inferredSources
    ) {
        /**
         * 先基于最小和最大配置建立可分配区间，确保每个来源至少保留一部分召回额度。
         */
        Map<RetrievalSource, Integer> min = normalizeTopKMap(
                ragProperties.getModularMinTopK(),
                Map.of(
                        RetrievalSource.KNOWLEDGE, 5,
                        RetrievalSource.MEMORY, 4,
                        RetrievalSource.PREFERENCE, 2
                )
        );
        Map<RetrievalSource, Integer> max = normalizeTopKMap(ragProperties.getModularMaxTopK(), ragProperties.getModularTopK());
        for (RetrievalSource source : RetrievalSource.values()) {
            if (max.get(source) < min.get(source)) {
                max.put(source, min.get(source));
            }
        }

        Map<RetrievalSource, Integer> dynamic = new EnumMap<>(RetrievalSource.class);
        for (RetrievalSource source : RetrievalSource.values()) {
            dynamic.put(source, min.get(source));
        }

        /**
         * 再根据延迟预算和查询复杂度决定本次应投入多少额外召回额度。
         */
        int minTotal = sumTopK(min);
        int maxTotal = sumTopK(max);
        int extraBudget = Math.max(0, maxTotal - minTotal);
        if (extraBudget == 0) {
            return dynamic;
        }

        double budgetFactor = latencyBudgetFactor(request);
        double complexityBoost = complexityBoost(queryObject);
        double demandFactor = clamp(budgetFactor + complexityBoost, 0.0, 1.0);
        int targetTotal = minTotal + (int) Math.round(extraBudget * demandFactor);
        int toAllocate = Math.max(0, targetTotal - minTotal);
        if (toAllocate == 0) {
            return dynamic;
        }

        /**
         * 最后按来源权重逐步分配剩余额度，让更相关的数据源获得更多召回空间。
         */
        Map<RetrievalSource, Double> weights = dynamicWeights(queryObject, inferredSources);
        while (toAllocate > 0) {
            RetrievalSource candidate = pickCandidateSource(weights, dynamic, max);
            if (candidate == null) {
                break;
            }
            dynamic.put(candidate, dynamic.get(candidate) + 1);
            toAllocate--;
        }

        return dynamic;
    }

    private Map<RetrievalSource, Integer> normalizeTopKMap(
            Map<RetrievalSource, Integer> configured,
            Map<RetrievalSource, Integer> fallback
    ) {
        Map<RetrievalSource, Integer> normalized = new EnumMap<>(RetrievalSource.class);
        for (RetrievalSource source : RetrievalSource.values()) {
            Integer configuredValue = configured == null ? null : configured.get(source);
            Integer fallbackValue = fallback == null ? null : fallback.get(source);
            int value = configuredValue != null ? configuredValue : (fallbackValue != null ? fallbackValue : 3);
            normalized.put(source, Math.max(1, value));
        }
        return normalized;
    }

    private int sumTopK(Map<RetrievalSource, Integer> topK) {
        int sum = 0;
        for (RetrievalSource source : RetrievalSource.values()) {
            sum += Math.max(1, topK.getOrDefault(source, 1));
        }
        return sum;
    }

    private double latencyBudgetFactor(RetrievalRequest request) {
        long maxLatencyMs = ragProperties.getDefaultTimeoutMs();
        if (request != null && request.getOptions() != null) {
            maxLatencyMs = request.getOptions().getMaxLatencyMs();
        }
        if (maxLatencyMs <= LOW_LATENCY_BUDGET_MS) {
            return 0.0;
        }
        if (maxLatencyMs >= HIGH_LATENCY_BUDGET_MS) {
            return 1.0;
        }
        return (double) (maxLatencyMs - LOW_LATENCY_BUDGET_MS) / (HIGH_LATENCY_BUDGET_MS - LOW_LATENCY_BUDGET_MS);
    }

    private double complexityBoost(QueryObject queryObject) {
        String queryType = queryObject.getQueryType() == null ? "" : queryObject.getQueryType();
        if ("analysis_reasoning".equalsIgnoreCase(queryType)) {
            return 0.30;
        }
        if ("multi_source_reasoning".equalsIgnoreCase(queryType)) {
            return 0.20;
        }

        if (containsAny(queryObject.getNormalizedQuery(), ragProperties.getAnalysisKeywords())) {
            return 0.20;
        }
        if (containsAny(queryObject.getNormalizedQuery(), ragProperties.getRecencyKeywords())) {
            return 0.10;
        }
        return 0.0;
    }

    private Map<RetrievalSource, Double> dynamicWeights(QueryObject queryObject, List<RetrievalSource> inferredSources) {
        Map<RetrievalSource, Double> weights = new EnumMap<>(RetrievalSource.class);
        weights.put(RetrievalSource.KNOWLEDGE, 0.50);
        weights.put(RetrievalSource.MEMORY, 0.35);
        weights.put(RetrievalSource.PREFERENCE, 0.15);

        if (inferredSources != null && !inferredSources.isEmpty()) {
            double bonus = 0.18;
            for (RetrievalSource source : inferredSources) {
                weights.put(source, weights.getOrDefault(source, 0.0) + bonus);
            }
        }

        List<String> tags = queryObject.getQueryTags() == null ? List.of() : queryObject.getQueryTags();
        if (tags.contains("memory_lookup")) {
            weights.put(RetrievalSource.MEMORY, weights.get(RetrievalSource.MEMORY) + 0.25);
        }
        if (tags.contains("preference_lookup")) {
            weights.put(RetrievalSource.PREFERENCE, weights.get(RetrievalSource.PREFERENCE) + 0.25);
        }
        if (tags.contains("needs_recency") || containsAny(queryObject.getNormalizedQuery(), ragProperties.getRecencyKeywords())) {
            weights.put(RetrievalSource.MEMORY, weights.get(RetrievalSource.MEMORY) + 0.20);
        }

        String queryType = queryObject.getQueryType() == null ? "" : queryObject.getQueryType();
        if ("analysis_reasoning".equalsIgnoreCase(queryType)) {
            weights.put(RetrievalSource.KNOWLEDGE, weights.get(RetrievalSource.KNOWLEDGE) + 0.20);
            weights.put(RetrievalSource.MEMORY, weights.get(RetrievalSource.MEMORY) + 0.20);
        }

        return weights;
    }

    private RetrievalSource pickCandidateSource(
            Map<RetrievalSource, Double> weights,
            Map<RetrievalSource, Integer> current,
            Map<RetrievalSource, Integer> max
    ) {
        RetrievalSource best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (RetrievalSource source : RetrievalSource.values()) {
            int cur = current.getOrDefault(source, 0);
            int cap = max.getOrDefault(source, cur);
            if (cur >= cap) {
                continue;
            }
            double normalizedWeight = weights.getOrDefault(source, 0.0) / (cur + 1.0);
            if (normalizedWeight > bestScore) {
                bestScore = normalizedWeight;
                best = source;
            }
        }
        return best;
    }

    private double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
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

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace("\uFEFF", "")
                .replace("\uFFFD", "")
                .trim();
    }
}
