package org.yilena.luna.rag.pipelines;

import org.springframework.stereotype.Component;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.fusion.EvidenceFusionService;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.models.RoutePlan;
import org.yilena.luna.rag.planner.ModelDrivenRagPlanner;
import org.yilena.luna.rag.rankers.EvidenceCompressor;
import org.yilena.luna.rag.rankers.EvidenceDeduplicator;
import org.yilena.luna.rag.rankers.EvidenceReranker;
import org.yilena.luna.rag.retrievers.BaseRetriever;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ModularPipeline extends AbstractRetrievalPipeline {

    public ModularPipeline(
            List<BaseRetriever> retrievers,
            EvidenceReranker evidenceReranker,
            EvidenceDeduplicator evidenceDeduplicator,
            EvidenceCompressor evidenceCompressor,
            RagProperties ragProperties,
            ModelDrivenRagPlanner modelDrivenRagPlanner,
            EvidenceFusionService evidenceFusionService
    ) {
        super(retrievers.stream().collect(java.util.stream.Collectors.toMap(BaseRetriever::source, it -> it)),
                evidenceReranker, evidenceDeduplicator, evidenceCompressor,
                ragProperties, modelDrivenRagPlanner, evidenceFusionService);
    }

    @Override
    public RetrievalRoute route() {
        return RetrievalRoute.MODULAR;
    }

    @Override
    public RetrievalResponse execute(QueryObject queryObject, RoutePlan plan, RetrievalRequest request) {
        QueryObject effectiveQuery = applyConditionalRewrite(queryObject, plan);
        List<RetrievalSource> routedSources = routeSources(effectiveQuery, plan, request);
        SourceRetrieveOutcome outcome = retrieveBySources(
                effectiveQuery,
                plan.getTopKConfig(),
                routedSources,
                true,
                true,
                request,
                resolveTimeoutMs(request)
        );
        Map<String, Object> meta = new HashMap<>(outcome.meta());
        meta.put("source_routing", routedSources.stream().map(RetrievalSource::value).toList());
        meta.put("rewrite_applied", plan.isNeedsRewrite());
        return RetrievalResponse.builder()
                .route(route())
                .rewrittenQuery(effectiveQuery.getRewrittenQuery())
                .evidences(ensureAllEvidenceBuckets(outcome.grouped()))
                .meta(meta)
                .build();
    }

    private QueryObject applyConditionalRewrite(QueryObject queryObject, RoutePlan plan) {
        if (plan != null && plan.isNeedsRewrite()) {
            return queryObject;
        }
        String fallbackQuery = queryObject.getNormalizedQuery() == null || queryObject.getNormalizedQuery().isBlank()
                ? queryObject.getOriginalQuery()
                : queryObject.getNormalizedQuery();
        return queryObject.toBuilder().rewrittenQuery(fallbackQuery).build();
    }

    private List<RetrievalSource> routeSources(QueryObject queryObject, RoutePlan plan, RetrievalRequest request) {
        List<RetrievalSource> base = plan.getSources() == null || plan.getSources().isEmpty()
                ? resolveSources(request)
                : new ArrayList<>(plan.getSources());
        List<RetrievalSource> inferred = inferredSourcesFromQuery(queryObject);
        if (inferred.isEmpty()) {
            return base;
        }
        List<RetrievalSource> routed = inferred.stream().filter(base::contains).toList();
        return routed.isEmpty() ? base : routed;
    }

    private List<RetrievalSource> inferredSourcesFromQuery(QueryObject queryObject) {
        if (queryObject.getPossibleFilters() != null && queryObject.getPossibleFilters().get("inferred_sources") instanceof List<?> raw) {
            List<RetrievalSource> parsed = raw.stream()
                    .map(String::valueOf)
                    .map(RetrievalSource::fromValue)
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .distinct()
                    .toList();
            if (!parsed.isEmpty()) {
                return parsed;
            }
        }

        String query = queryObject.getRewrittenQuery() == null ? "" : queryObject.getRewrittenQuery();
        Set<RetrievalSource> inferred = new LinkedHashSet<>();
        for (Map.Entry<RetrievalSource, List<String>> entry : getRagProperties().sourceKeywordMap().entrySet()) {
            if (containsAny(query, entry.getValue())) {
                inferred.add(entry.getKey());
            }
        }
        if (containsAny(query, getRagProperties().getMultiSourceKeywords())) {
            inferred.addAll(List.of(RetrievalSource.values()));
        }
        return new ArrayList<>(inferred);
    }

    private boolean containsAny(String query, List<String> keywords) {
        if (query == null || query.isBlank()) {
            return false;
        }
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (query.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
