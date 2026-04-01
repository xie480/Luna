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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RouteSelector {

    private final RagProperties ragProperties;

    public RoutePlan selectPlan(QueryObject queryObject, RetrievalRequest request) {
        List<RetrievalRoute> allowedRoutes = request.getAllowedRoutes() == null || request.getAllowedRoutes().isEmpty()
                ? RetrievalRoute.all()
                : request.getAllowedRoutes();
        List<RetrievalSource> scopedSources = request.getSourceScope() == null || request.getSourceScope().isEmpty()
                ? RetrievalSource.all()
                : new ArrayList<>(request.getSourceScope());

        List<RetrievalSource> inferredSources = inferSources(queryObject, scopedSources);
        RetrievalRoute route = selectRoute(queryObject, allowedRoutes, inferredSources.size());

        return RoutePlan.builder()
                .route(route)
                .sources(inferredSources)
                .queryType(queryObject.getQueryType())
                .needsRewrite(shouldRewrite(queryObject, route))
                .needsRerank(shouldRerank(queryObject))
                .topKConfig(topKByRoute(route))
                .build();
    }

    private RetrievalRoute selectRoute(QueryObject queryObject, List<RetrievalRoute> allowedRoutes, int inferredSourceCount) {
        String query = queryObject.getNormalizedQuery();
        String queryType = queryObject.getQueryType();
        List<RagProperties.RetrievalRouteRule> configuredPriority = ragProperties.getRoutePriority();
        List<RagProperties.RetrievalRouteRule> effectivePriority =
                configuredPriority == null || configuredPriority.isEmpty()
                        ? List.of(
                        RagProperties.RetrievalRouteRule.SEARCH,
                        RagProperties.RetrievalRouteRule.NATIVE,
                        RagProperties.RetrievalRouteRule.MODULAR,
                        RagProperties.RetrievalRouteRule.AGENTIC
                )
                        : configuredPriority;

        for (RagProperties.RetrievalRouteRule rule : effectivePriority) {
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

    private Map<RetrievalSource, Integer> topKByRoute(RetrievalRoute route) {
        return switch (route) {
            case SEARCH -> ragProperties.getSearchTopK();
            case NATIVE -> ragProperties.getNativeTopK();
            case MODULAR -> ragProperties.getModularTopK();
            case AGENTIC -> ragProperties.getAgenticTopK();
        };
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
