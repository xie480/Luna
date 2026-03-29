package org.yilena.luna.rag.pipelines;

import org.springframework.stereotype.Component;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.models.RoutePlan;
import org.yilena.luna.rag.rankers.EvidenceCompressor;
import org.yilena.luna.rag.rankers.EvidenceDeduplicator;
import org.yilena.luna.rag.rankers.EvidenceReranker;
import org.yilena.luna.rag.retrievers.BaseRetriever;

import java.util.List;
import java.util.Map;

/** Native 检索流水线，基于查询特征选择主数据源执行单源召回。 */
@Component
public class NativePipeline extends AbstractRetrievalPipeline {

    public NativePipeline(
            List<BaseRetriever> retrievers,
            EvidenceReranker evidenceReranker,
            EvidenceDeduplicator evidenceDeduplicator,
            EvidenceCompressor evidenceCompressor
    ) {
        super(retrievers.stream().collect(java.util.stream.Collectors.toMap(BaseRetriever::source, it -> it)),
                evidenceReranker, evidenceDeduplicator, evidenceCompressor);
    }

    @Override
    public RetrievalRoute route() {
        return RetrievalRoute.NATIVE;
    }

    @Override
    public RetrievalResponse execute(QueryObject queryObject, RoutePlan plan, RetrievalRequest request) {
        RetrievalSource primary = detectPrimarySource(queryObject, request);
        Map<RetrievalSource, List<org.yilena.luna.rag.models.Evidence>> evidences =
                retrieveBySources(queryObject, plan.getTopKConfig(), List.of(primary), true, false);
        return RetrievalResponse.builder()
                .route(route())
                .rewrittenQuery(queryObject.getRewrittenQuery())
                .evidences(evidences)
                .build();
    }

    private RetrievalSource detectPrimarySource(QueryObject queryObject, RetrievalRequest request) {
        List<RetrievalSource> scoped = resolveSources(request);
        String query = queryObject.getNormalizedQuery();
        if (query.contains("偏好") && scoped.contains(RetrievalSource.PREFERENCE)) {
            return RetrievalSource.PREFERENCE;
        }
        if ((query.contains("记忆") || query.contains("之前") || query.contains("过去")) && scoped.contains(RetrievalSource.MEMORY)) {
            return RetrievalSource.MEMORY;
        }
        if (scoped.contains(RetrievalSource.KNOWLEDGE)) {
            return RetrievalSource.KNOWLEDGE;
        }
        return scoped.get(0);
    }
}
