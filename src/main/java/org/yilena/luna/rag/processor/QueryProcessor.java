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

    private final EmbeddingProvider embeddingProvider; // 声明成员字段
    private final RagProperties ragProperties; // 声明成员字段
    private final ModelDrivenRagPlanner modelDrivenRagPlanner; // 声明成员字段

    public QueryObject process(RetrievalRequest request) { // 定义方法签名
        String original = request.getQuery() == null ? "" : request.getQuery(); // 执行赋值操作
        String normalized = normalize(original); // 执行赋值操作
        ModelDrivenRagPlanner.QueryPlanDecision planDecision = modelDrivenRagPlanner.planQuery(original, normalized, request); // 执行赋值操作
        String queryType = planDecision.getQueryType() == null ? detectQueryType(normalized) : planDecision.getQueryType(); // 执行赋值操作
        String rewritten = planDecision.getRewrittenQuery() == null // 执行赋值操作
                ? rewrite(normalized, queryType) // 执行当前逻辑
                : planDecision.getRewrittenQuery(); // 执行语句逻辑
        String embedding = embeddingProvider.embedding(rewritten); // 执行赋值操作

        Map<String, Object> filters = new HashMap<>(); // 执行赋值操作
        filters.put("query_type", queryType); // 执行语句逻辑
        if (planDecision.getRouteHint() != null) { // 进行条件判断
            filters.put("route_hint", planDecision.getRouteHint().value()); // 执行语句逻辑
        } // 结束当前代码块
        filters.put("query_complexity", planDecision.getComplexity()); // 执行语句逻辑

        return QueryObject.builder() // 返回处理结果
                .originalQuery(original) // 执行当前逻辑
                .normalizedQuery(normalized) // 执行当前逻辑
                .rewrittenQuery(rewritten) // 执行当前逻辑
                .sessionId(request.getSessionId()) // 执行当前逻辑
                .conversationContext(request.getConversationContext()) // 执行当前逻辑
                .queryType(queryType) // 执行当前逻辑
                .possibleFilters(filters) // 执行当前逻辑
                .embedding(embedding) // 执行当前逻辑
                .build(); // 执行语句逻辑
    } // 结束当前代码块

    private String normalize(String query) { // 定义方法签名
        return query == null ? "" : query.trim().replaceAll("\\s+", " "); // 返回处理结果
    } // 结束当前代码块

    private String detectQueryType(String query) { // 定义方法签名
        if (containsAny(query, ragProperties.getPreciseKeywords())) { // 进行条件判断
            return "precise_lookup"; // 返回处理结果
        } // 结束当前代码块
        if (containsAny(query, ragProperties.getAnalysisKeywords())) { // 进行条件判断
            return "analysis_reasoning"; // 返回处理结果
        } // 结束当前代码块
        if (query.contains("结合") || query.contains("根据") || query.contains("偏好")) { // 进行条件判断
            return "multi_source_reasoning"; // 返回处理结果
        } // 结束当前代码块
        return "general_retrieval"; // 返回处理结果
    } // 结束当前代码块

    private String rewrite(String normalized, String queryType) { // 定义方法签名
        if ("analysis_reasoning".equals(queryType)) { // 进行条件判断
            return "请围绕问题进行结构化检索与分析：" + normalized; // 返回处理结果
        } // 结束当前代码块
        return normalized; // 返回处理结果
    } // 结束当前代码块

    private boolean containsAny(String query, java.util.List<String> keywords) { // 定义方法签名
        if (query == null || query.isBlank() || keywords == null || keywords.isEmpty()) { // 进行条件判断
            return false; // 返回处理结果
        } // 结束当前代码块
        return keywords.stream().anyMatch(query::contains); // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
