package org.yilena.luna.rag.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.models.RoutePlan;
import org.yilena.luna.rag.pipelines.RetrievalPipeline;
import org.yilena.luna.rag.processor.QueryProcessor;
import org.yilena.luna.rag.router.RouteSelector;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 通用 RAG 主编排服务：
 * 1) query 预处理
 * 2) 路由决策
 * 3) pipeline 执行
 * 4) 统一补充 meta 并输出
 */
public class RetrievalServiceImpl implements RetrievalService {

    // 负责 query 清洗、分类、rewrite、embedding
    private final QueryProcessor queryProcessor;
    // 负责 route 选择与 top-k 策略生成
    private final RouteSelector routeSelector;
    // 所有 pipeline 实现（Search/Native/Modular/Agentic）由 Spring 注入
    private final List<RetrievalPipeline> pipelines;

    @Override
    public RetrievalResponse retrieve(RetrievalRequest request) {
        // 统一计时，作为可观测指标写入 meta
        long start = System.currentTimeMillis();
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return emptyResponse(RetrievalRoute.SEARCH, "empty_query", 0);
        }

        // 预处理 query -> 路由计划 -> pipeline 选择
        QueryObject queryObject = queryProcessor.process(request);
        RoutePlan plan = routeSelector.selectPlan(queryObject, request);
        RetrievalPipeline pipeline = selectPipeline(plan.getRoute());
        if (pipeline == null) {
            // 理论上不应发生：若缺失 pipeline，返回空结果但不中断主流程
            return emptyResponse(plan.getRoute(), queryObject.getRewrittenQuery(), elapsed(start));
        }

        // 执行目标 pipeline，并统一补齐 meta
        RetrievalResponse rawResponse = pipeline.execute(queryObject, plan, request);
        Map<String, Object> meta = new HashMap<>();
        meta.put("sources_used", request.getSourceScope() == null ? Collections.emptyList() : request.getSourceScope().stream().map(RetrievalSource::value).toList());
        meta.put("latency_ms", elapsed(start));
        meta.put("query_type", queryObject.getQueryType());
        meta.put("session_id", request.getSessionId());

        if (rawResponse.getMeta() != null && !rawResponse.getMeta().isEmpty()) {
            // 保留 pipeline 自定义 meta（如 agentic fallback 标记）
            meta.putAll(rawResponse.getMeta());
        }
        RetrievalResponse response = RetrievalResponse.builder()
                .route(rawResponse.getRoute())
                .rewrittenQuery(rawResponse.getRewrittenQuery())
                .evidences(rawResponse.getEvidences())
                .meta(meta)
                .build();

        log.info("eventType=RAG_RETRIEVE route={} queryType={} latencyMs={} sessionId={}",
                response.getRoute().value(),
                queryObject.getQueryType(),
                meta.get("latency_ms"),
                request.getSessionId());
        return response;
    }

    /**
     * 根据路由类型选择对应 pipeline 实现。
     */
    private RetrievalPipeline selectPipeline(RetrievalRoute route) {
        return pipelines.stream().filter(p -> p.route() == route).findFirst().orElse(null);
    }

    /**
     * 生成统一空响应，避免上层 NPE。
     */
    private RetrievalResponse emptyResponse(RetrievalRoute route, String rewrittenQuery, long latencyMs) {
        Map<RetrievalSource, java.util.List<org.yilena.luna.rag.models.Evidence>> emptyEvidences = new EnumMap<>(RetrievalSource.class);
        for (RetrievalSource source : RetrievalSource.values()) {
            emptyEvidences.put(source, List.of());
        }
        return RetrievalResponse.builder()
                .route(route)
                .rewrittenQuery(rewrittenQuery)
                .evidences(emptyEvidences)
                .meta(Map.of("latency_ms", latencyMs, "query_type", "none"))
                .build();
    }

    /**
     * 计算耗时（毫秒）。
     */
    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
