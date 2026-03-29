package org.yilena.luna.rag.router;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RoutePlan;

import java.util.List;
import java.util.Map;

/** 路由选择器，按查询特征与配置规则生成 RoutePlan 和 topK 策略。 */
@Component
@RequiredArgsConstructor
public class RouteSelector {

    private final RagProperties ragProperties;

    public RoutePlan selectPlan(QueryObject queryObject, RetrievalRequest request) {
        List<RetrievalRoute> allowedRoutes = request.getAllowedRoutes() == null || request.getAllowedRoutes().isEmpty()
                ? RetrievalRoute.all()
                : request.getAllowedRoutes();

        RetrievalRoute route = selectRoute(queryObject, request, allowedRoutes);
        return RoutePlan.builder()
                .route(route)
                .queryType(queryObject.getQueryType())
                .needsRewrite(route == RetrievalRoute.MODULAR || route == RetrievalRoute.AGENTIC)
                .needsRerank(true)
                .topKConfig(topKByRoute(route))
                .build();
    }

    private RetrievalRoute selectRoute(QueryObject queryObject, RetrievalRequest request, List<RetrievalRoute> allowedRoutes) {
        String query = queryObject.getNormalizedQuery();
        int sourceCount = request.getSourceScope() == null || request.getSourceScope().isEmpty()
                ? 3
                : request.getSourceScope().size();

        if (containsAny(query, ragProperties.getPreciseKeywords()) && allowedRoutes.contains(RetrievalRoute.SEARCH)) {
            return RetrievalRoute.SEARCH;
        }
        if (containsAny(query, ragProperties.getAnalysisKeywords()) && allowedRoutes.contains(RetrievalRoute.AGENTIC)) {
            return RetrievalRoute.AGENTIC;
        }
        if (sourceCount == 1 && allowedRoutes.contains(RetrievalRoute.NATIVE)) {
            return RetrievalRoute.NATIVE;
        }
        if (allowedRoutes.contains(RetrievalRoute.MODULAR)) {
            return RetrievalRoute.MODULAR;
        }
        return allowedRoutes.get(0);
    }

    private Map<org.yilena.luna.rag.models.RetrievalSource, Integer> topKByRoute(RetrievalRoute route) {
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
        return keywords.stream().anyMatch(query::contains);
    }
}
