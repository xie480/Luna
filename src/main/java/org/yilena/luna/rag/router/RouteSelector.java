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

/** Route selector with model hint first and heuristic fallback. */
@Component
@RequiredArgsConstructor
public class RouteSelector {

    private final RagProperties ragProperties; // 声明成员字段

    public RoutePlan selectPlan(QueryObject queryObject, RetrievalRequest request) { // 定义方法签名
        List<RetrievalRoute> allowedRoutes = request.getAllowedRoutes() == null || request.getAllowedRoutes().isEmpty() // 执行赋值操作
                ? RetrievalRoute.all() // 执行当前逻辑
                : request.getAllowedRoutes(); // 执行语句逻辑

        RetrievalRoute route = selectRoute(queryObject, request, allowedRoutes); // 执行赋值操作
        return RoutePlan.builder() // 返回处理结果
                .route(route) // 执行当前逻辑
                .queryType(queryObject.getQueryType()) // 执行当前逻辑
                .needsRewrite(route == RetrievalRoute.MODULAR || route == RetrievalRoute.AGENTIC) // 执行赋值操作
                .needsRerank(true) // 执行当前逻辑
                .topKConfig(topKByRoute(route)) // 执行当前逻辑
                .build(); // 执行语句逻辑
    } // 结束当前代码块

    private RetrievalRoute selectRoute(QueryObject queryObject, RetrievalRequest request, List<RetrievalRoute> allowedRoutes) { // 定义方法签名
        String query = queryObject.getNormalizedQuery(); // 执行赋值操作
        RetrievalRoute hintedRoute = resolveHintedRoute(queryObject, allowedRoutes); // 执行赋值操作
        if (hintedRoute != null) { // 进行条件判断
            return hintedRoute; // 返回处理结果
        } // 结束当前代码块

        int sourceCount = request.getSourceScope() == null || request.getSourceScope().isEmpty() // 执行赋值操作
                ? 3 // 执行当前逻辑
                : request.getSourceScope().size(); // 执行语句逻辑

        if (containsAny(query, ragProperties.getPreciseKeywords()) && allowedRoutes.contains(RetrievalRoute.SEARCH)) { // 进行条件判断
            return RetrievalRoute.SEARCH; // 返回处理结果
        } // 结束当前代码块
        if (containsAny(query, ragProperties.getAnalysisKeywords()) && allowedRoutes.contains(RetrievalRoute.AGENTIC)) { // 进行条件判断
            return RetrievalRoute.AGENTIC; // 返回处理结果
        } // 结束当前代码块
        if (sourceCount == 1 && allowedRoutes.contains(RetrievalRoute.NATIVE)) { // 进行条件判断
            return RetrievalRoute.NATIVE; // 返回处理结果
        } // 结束当前代码块
        if (allowedRoutes.contains(RetrievalRoute.MODULAR)) { // 进行条件判断
            return RetrievalRoute.MODULAR; // 返回处理结果
        } // 结束当前代码块
        return allowedRoutes.get(0); // 返回处理结果
    } // 结束当前代码块

    private RetrievalRoute resolveHintedRoute(QueryObject queryObject, List<RetrievalRoute> allowedRoutes) { // 定义方法签名
        if (queryObject.getPossibleFilters() == null) { // 进行条件判断
            return null; // 返回处理结果
        } // 结束当前代码块
        Object routeHint = queryObject.getPossibleFilters().get("route_hint"); // 执行赋值操作
        if (!(routeHint instanceof String routeHintValue) || routeHintValue.isBlank()) { // 进行条件判断
            return null; // 返回处理结果
        } // 结束当前代码块
        return RetrievalRoute.fromValue(routeHintValue) // 返回处理结果
                .filter(allowedRoutes::contains) // 执行当前逻辑
                .orElse(null); // 执行语句逻辑
    } // 结束当前代码块

    private Map<org.yilena.luna.rag.models.RetrievalSource, Integer> topKByRoute(RetrievalRoute route) { // 定义方法签名
        return switch (route) { // 返回处理结果
            case SEARCH -> ragProperties.getSearchTopK(); // 命中分支条件
            case NATIVE -> ragProperties.getNativeTopK(); // 命中分支条件
            case MODULAR -> ragProperties.getModularTopK(); // 命中分支条件
            case AGENTIC -> ragProperties.getAgenticTopK(); // 命中分支条件
        }; // 执行语句逻辑
    } // 结束当前代码块

    private boolean containsAny(String query, List<String> keywords) { // 定义方法签名
        if (query == null || query.isBlank() || keywords == null || keywords.isEmpty()) { // 进行条件判断
            return false; // 返回处理结果
        } // 结束当前代码块
        return keywords.stream().anyMatch(query::contains); // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
