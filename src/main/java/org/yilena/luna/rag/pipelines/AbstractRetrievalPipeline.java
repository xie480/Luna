package org.yilena.luna.rag.pipelines;

import lombok.RequiredArgsConstructor;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.models.RoutePlan;
import org.yilena.luna.rag.rankers.EvidenceCompressor;
import org.yilena.luna.rag.rankers.EvidenceDeduplicator;
import org.yilena.luna.rag.rankers.EvidenceReranker;
import org.yilena.luna.rag.retrievers.BaseRetriever;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 检索流水线抽象基类，封装多数据源召回与证据后处理通用逻辑。
 */
@RequiredArgsConstructor
public abstract class AbstractRetrievalPipeline implements RetrievalPipeline {

    private final Map<RetrievalSource, BaseRetriever> retrieverMap;
    private final EvidenceReranker evidenceReranker;
    private final EvidenceDeduplicator evidenceDeduplicator;
    private final EvidenceCompressor evidenceCompressor;

    protected Map<RetrievalSource, List<Evidence>> retrieveBySources(
            QueryObject queryObject,
            Map<RetrievalSource, Integer> topKConfig,
            List<RetrievalSource> sources,
            boolean rerank,
            boolean compress
    ) {
        Map<RetrievalSource, List<Evidence>> result = new EnumMap<>(RetrievalSource.class);
        List<RetrievalSource> targetSources = sources == null || sources.isEmpty() ? RetrievalSource.all() : sources;
        for (RetrievalSource source : targetSources) {
            BaseRetriever retriever = retrieverMap.get(source);
            if (retriever == null) {
                result.put(source, Collections.emptyList());
                continue;
            }
            int topK = topKConfig.getOrDefault(source, 3);
            List<Evidence> evidences = retriever.retrieve(queryObject, topK, queryObject.getPossibleFilters());
            evidences = evidenceDeduplicator.deduplicate(evidences);
            if (rerank) {
                evidences = evidenceReranker.rerank(queryObject.getRewrittenQuery(), evidences, topK);
            }
            if (compress) {
                evidences = evidenceCompressor.compress(evidences);
            }
            result.put(source, evidences);
        }
        return result;
    }

    protected List<Evidence> flatten(Map<RetrievalSource, List<Evidence>> grouped) {
        List<Evidence> all = new ArrayList<>();
        grouped.values().forEach(all::addAll);
        return all;
    }

    protected List<RetrievalSource> resolveSources(RetrievalRequest request) {
        return request.getSourceScope() == null || request.getSourceScope().isEmpty()
                ? RetrievalSource.all()
                : request.getSourceScope();
    }
}
