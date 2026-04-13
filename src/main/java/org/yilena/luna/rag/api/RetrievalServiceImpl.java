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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 该服务实现负责串联查询处理、路由选择和具体检索流水线，并统一补齐检索元信息输出。
 */
public class RetrievalServiceImpl implements RetrievalService {

    /**
     * 查询预处理器，用于完成归一化、改写和过滤信号提取。
     */
    private final QueryProcessor queryProcessor;
    /**
     * 路由选择器，用于决定当前请求应该进入哪条检索流水线。
     */
    private final RouteSelector routeSelector;
    /**
     * 所有已注册的检索流水线实现。
     */
    private final List<RetrievalPipeline> pipelines;

    @Override
    /**
     * 执行统一检索入口，依次完成请求校验、查询处理、路由分发和结果包装。
     */
    public RetrievalResponse retrieve(RetrievalRequest request) {
        long start = System.currentTimeMillis();

        /**
         * 空查询直接返回空响应，避免无意义进入后续检索链路。
         */
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return emptyResponse(RetrievalRoute.SEARCH, "empty_query", 0);
        }

        /**
         * 先把原始请求处理为统一查询对象，再根据查询特征生成路由计划。
         * queryProcessor.process 负责查询标准化、改写和类型识别。
         * routeSelector.selectPlan 基于查询对象和请求配置选择最优检索路由。
         */
        QueryObject queryObject = queryProcessor.process(request);
        RoutePlan plan = routeSelector.selectPlan(queryObject, request);

        /**
         * 根据路由计划选择对应的检索流水线执行引擎。
         * 如果未找到匹配的流水线，返回空响应并记录耗时。
         */
        RetrievalPipeline pipeline = selectPipeline(plan.getRoute());
        if (pipeline == null) {
            return emptyResponse(plan.getRoute(), queryObject.getRewrittenQuery(), elapsed(start));
        }

        /**
         * 执行命中的检索流水线，并在响应上补充统一元信息和调试数据。
         * rawResponse 包含从各数据源召回的原始证据列表。
         */
        RetrievalResponse rawResponse = pipeline.execute(queryObject, plan, request);

        /**
         * 构建响应元信息映射，包含来源统计、延迟、查询类型等关键字段。
         */
        Map<String, Object> meta = new HashMap<>();
        meta.put("sources_used", resolveSourcesUsed(rawResponse, request));
        meta.put("latency_ms", elapsed(start));
        meta.put("query_type", queryObject.getQueryType());
        meta.put("session_id", request.getSessionId());
        meta.put("needs_rerank", plan.isNeedsRerank());

        /**
         * 合并流水线内部产生的元信息到统一元数据容器中。
         */
        if (rawResponse.getMeta() != null && !rawResponse.getMeta().isEmpty()) {
            meta.putAll(rawResponse.getMeta());
        }

        /**
         * 调试模式下追加路由计划与改写信息，便于排查检索策略命中原因。
         * 将路由选择依据、查询改写前后对比等诊断数据注入 debug 字段。
         */
        if (request.getOptions() != null && request.getOptions().isDebug()) {
            Map<String, Object> mergedDebug = new HashMap<>();

            /**
             * 保留流水线已有的调试信息，避免覆盖底层诊断数据。
             */
            if (meta.get("debug") instanceof Map<?, ?> existedDebug) {
                existedDebug.forEach((k, v) -> mergedDebug.put(String.valueOf(k), v));
            }

            /**
             * 注入路由计划详情，包括选中的路由、允许的候选路由、
             * 数据源范围、是否需要改写/重排以及 Top-K 配置。
             */
            mergedDebug.put("route_plan", Map.of(
                    "selected_route", plan.getRoute().value(),
                    "allowed_routes", request.getAllowedRoutes() == null ? List.of() : request.getAllowedRoutes().stream().map(RetrievalRoute::value).toList(),
                    "selected_sources", plan.getSources() == null ? List.of() : plan.getSources().stream().map(RetrievalSource::value).toList(),
                    "needs_rewrite", plan.isNeedsRewrite(),
                    "needs_rerank", plan.isNeedsRerank(),
                    "top_k_config", plan.getTopKConfig() == null ? Map.of() : plan.getTopKConfig().entrySet().stream()
                            .collect(Collectors.toMap(entry -> entry.getKey().value(), Map.Entry::getValue))
            ));

            /**
             * 注入查询改写信息，记录原始查询、改写后查询和识别的查询类型。
             */
            mergedDebug.put("query", Map.of(
                    "original", request.getQuery(),
                    "rewritten", queryObject.getRewrittenQuery(),
                    "query_type", queryObject.getQueryType()
            ));
            meta.put("debug", mergedDebug);
        }

        /**
         * 返回经过统一包装的检索结果，并输出关键链路日志便于后续追踪。
         * 最终响应包含路由标识、改写查询、证据列表和完整元信息。
         */
        RetrievalResponse response = RetrievalResponse.builder()
                .route(rawResponse.getRoute())
                .rewrittenQuery(rawResponse.getRewrittenQuery())
                .evidences(rawResponse.getEvidences())
                .evidenceRoleGroups(rawResponse.getEvidenceRoleGroups())
                .meta(meta)
                .build();

        log.info("eventType=RAG_RETRIEVE route={} queryType={} latencyMs={} sessionId={}",
                response.getRoute().value(),
                queryObject.getQueryType(),
                meta.get("latency_ms"),
                request.getSessionId());
        return response;
    }


    private List<String> resolveSourcesUsed(RetrievalResponse response, RetrievalRequest request) {
        if (response != null && response.getMeta() != null && response.getMeta().get("hit_sources") instanceof List<?> hitSources) {
            return hitSources.stream().map(String::valueOf).toList();
        }
        if (response != null && response.getEvidences() != null && !response.getEvidences().isEmpty()) {
            return response.getEvidences().entrySet().stream()
                    .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                    .map(entry -> entry.getKey().value())
                    .toList();
        }
        if (request.getSourceScope() == null) {
            return Collections.emptyList();
        }
        return request.getSourceScope().stream().map(RetrievalSource::value).toList();
    }

    private RetrievalPipeline selectPipeline(RetrievalRoute route) {
        return pipelines.stream().filter(p -> p.route() == route).findFirst().orElse(null);
    }

    private RetrievalResponse emptyResponse(RetrievalRoute route, String rewrittenQuery, long latencyMs) {
        Map<RetrievalSource, List<org.yilena.luna.rag.models.Evidence>> emptyEvidences = new EnumMap<>(RetrievalSource.class);
        for (RetrievalSource source : RetrievalSource.values()) {
            emptyEvidences.put(source, List.of());
        }
        return RetrievalResponse.builder()
                .route(route)
                .rewrittenQuery(rewrittenQuery)
                .evidences(emptyEvidences)
                .evidenceRoleGroups(Map.of())
                .meta(Map.of("latency_ms", latencyMs, "query_type", "none"))
                .build();
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
