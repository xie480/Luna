package org.yilena.luna.rag.pipelines;

import org.springframework.stereotype.Component;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.fusion.EvidenceFusionService;
import org.yilena.luna.rag.models.Evidence;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class NativePipeline extends AbstractRetrievalPipeline {

    public NativePipeline(
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
        return RetrievalRoute.NATIVE;
    }

    @Override
    public RetrievalResponse execute(QueryObject queryObject, RoutePlan plan, RetrievalRequest request) {
        RetrievalSource primary = detectPrimarySource(queryObject, request);
        SourceRetrieveOutcome outcome = retrieveBySources(
                queryObject,
                plan.getTopKConfig(),
                List.of(primary),
                true,
                false,
                false,
                request,
                resolveTimeoutMs(request)
        );
        Map<String, Object> meta = new HashMap<>(outcome.meta());
        Map<RetrievalSource, List<Evidence>> grouped =
                ensureAllEvidenceBuckets(outcome.grouped());
        return RetrievalResponse.builder()
                .route(route())
                .rewrittenQuery(queryObject.getRewrittenQuery())
                .evidences(grouped)
                .evidenceRoleGroups(buildEvidenceRoleGroups(grouped))
                .meta(meta)
                .build();
    }

    private RetrievalSource detectPrimarySource(QueryObject queryObject, RetrievalRequest request) {
        List<RetrievalSource> scoped = resolveSources(request);

        List<RetrievalSource> inferred = inferredSources(queryObject, scoped);
        List<RetrievalSource> priority = getRagProperties().getNativePrimarySourcePriority();
        if (priority != null && !priority.isEmpty()) {
            for (RetrievalSource source : priority) {
                if (scoped.contains(source) && inferred.contains(source)) {
                    return source;
                }
            }
        }

        String query = queryObject.getNormalizedQuery() == null ? "" : queryObject.getNormalizedQuery();
        for (RetrievalSource source : scoped) {
            if (containsAny(query, getRagProperties().keywordsOf(source))) {
                return source;
            }
        }

        if (scoped.contains(RetrievalSource.KNOWLEDGE)) {
            return RetrievalSource.KNOWLEDGE;
        }
        return scoped.get(0);
    }

    private List<RetrievalSource> inferredSources(QueryObject queryObject, List<RetrievalSource> scoped) {
        if (queryObject.getPossibleFilters() == null) {
            return List.of();
        }
        Object raw = queryObject.getPossibleFilters().get("inferred_sources");
        if (!(raw instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .map(String::valueOf)
                .map(RetrievalSource::fromValue)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .filter(scoped::contains)
                .distinct()
                .toList();
    }

    private boolean containsAny(String query, List<String> keywords) {
        if (query == null || query.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        return keywords.stream().anyMatch(query::contains);
    }
}
