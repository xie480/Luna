package org.yilena.luna.rag.pipelines;

import org.springframework.stereotype.Component;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.fusion.EvidenceFusionService;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RoutePlan;
import org.yilena.luna.rag.planner.ModelDrivenRagPlanner;
import org.yilena.luna.rag.rankers.EvidenceCompressor;
import org.yilena.luna.rag.rankers.EvidenceDeduplicator;
import org.yilena.luna.rag.rankers.EvidenceReranker;
import org.yilena.luna.rag.retrievers.BaseRetriever;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Search 检索流水线，面向精确检索场景按路由数据源召回结果。 */
@Component
public class SearchPipeline extends AbstractRetrievalPipeline {

    public SearchPipeline(
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
        return RetrievalRoute.SEARCH;
    }

    @Override
    public RetrievalResponse execute(QueryObject queryObject, RoutePlan plan, RetrievalRequest request) {
        Map<String, Object> searchFilters = new HashMap<>();
        if (queryObject.getPossibleFilters() != null) {
            searchFilters.putAll(queryObject.getPossibleFilters());
        }
        searchFilters.put("search_mode", "exact_first");
        searchFilters.put("retrieval_strategy", "keyword_fts_exact_then_vector");
        QueryObject searchQuery = queryObject.toBuilder().possibleFilters(searchFilters).build();

        SourceRetrieveOutcome outcome = retrieveBySources(
                searchQuery,
                plan.getTopKConfig(),
                plan.getSources() == null || plan.getSources().isEmpty() ? resolveSources(request) : new ArrayList<>(plan.getSources()),
                true,
                false,
                request,
                resolveTimeoutMs(request)
        );
        Map<String, Object> meta = new HashMap<>(outcome.meta());
        return RetrievalResponse.builder()
                .route(route())
                .rewrittenQuery(queryObject.getRewrittenQuery())
                .evidences(outcome.grouped())
                .meta(meta)
                .build();
    }
}
