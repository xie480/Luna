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
public class RetrievalServiceImpl implements RetrievalService {

    private final QueryProcessor queryProcessor;
    private final RouteSelector routeSelector;
    private final List<RetrievalPipeline> pipelines;

    @Override
    public RetrievalResponse retrieve(RetrievalRequest request) {
        long start = System.currentTimeMillis();
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return emptyResponse(RetrievalRoute.SEARCH, "empty_query", 0);
        }

        QueryObject queryObject = queryProcessor.process(request);
        RoutePlan plan = routeSelector.selectPlan(queryObject, request);
        RetrievalPipeline pipeline = selectPipeline(plan.getRoute());
        if (pipeline == null) {
            return emptyResponse(plan.getRoute(), queryObject.getRewrittenQuery(), elapsed(start));
        }

        RetrievalResponse rawResponse = pipeline.execute(queryObject, plan, request);
        Map<String, Object> meta = new HashMap<>();
        meta.put("sources_used", resolveSourcesUsed(rawResponse, request));
        meta.put("latency_ms", elapsed(start));
        meta.put("query_type", queryObject.getQueryType());
        meta.put("session_id", request.getSessionId());
        meta.put("needs_rerank", plan.isNeedsRerank());
        if (rawResponse.getMeta() != null && !rawResponse.getMeta().isEmpty()) {
            meta.putAll(rawResponse.getMeta());
        }
        if (request.getOptions() != null && request.getOptions().isDebug()) {
            Map<String, Object> mergedDebug = new HashMap<>();
            if (meta.get("debug") instanceof Map<?, ?> existedDebug) {
                existedDebug.forEach((k, v) -> mergedDebug.put(String.valueOf(k), v));
            }
            mergedDebug.put("route_plan", Map.of(
                    "selected_route", plan.getRoute().value(),
                    "allowed_routes", request.getAllowedRoutes() == null ? List.of() : request.getAllowedRoutes().stream().map(RetrievalRoute::value).toList(),
                    "selected_sources", plan.getSources() == null ? List.of() : plan.getSources().stream().map(RetrievalSource::value).toList(),
                    "needs_rewrite", plan.isNeedsRewrite(),
                    "needs_rerank", plan.isNeedsRerank(),
                    "top_k_config", plan.getTopKConfig() == null ? Map.of() : plan.getTopKConfig().entrySet().stream()
                            .collect(Collectors.toMap(entry -> entry.getKey().value(), Map.Entry::getValue))
            ));
            mergedDebug.put("query", Map.of(
                    "original", request.getQuery(),
                    "rewritten", queryObject.getRewrittenQuery(),
                    "query_type", queryObject.getQueryType()
            ));
            meta.put("debug", mergedDebug);
        }

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
