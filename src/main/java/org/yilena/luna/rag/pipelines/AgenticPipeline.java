package org.yilena.luna.rag.pipelines;

import org.springframework.stereotype.Component;
import org.yilena.luna.rag.models.Evidence;
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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Agentic 检索流水线，支持弱证据场景下的二轮回退召回。
 */
@Component
public class AgenticPipeline extends AbstractRetrievalPipeline {

    public AgenticPipeline(
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
        return RetrievalRoute.AGENTIC;
    }

    @Override
    public RetrievalResponse execute(QueryObject queryObject, RoutePlan plan, RetrievalRequest request) {
        List<RetrievalSource> sources = resolveSources(request);
        Map<RetrievalSource, List<Evidence>> firstRound =
                retrieveBySources(queryObject, plan.getTopKConfig(), sources, true, true);

        boolean weakEvidence = firstRound.values().stream().mapToInt(List::size).sum() < 3;
        if (!weakEvidence) {
            return RetrievalResponse.builder()
                    .route(route())
                    .rewrittenQuery(queryObject.getRewrittenQuery())
                    .evidences(firstRound)
                    .build();
        }

        QueryObject fallbackQuery = queryObject.toBuilder()
                .rewrittenQuery(queryObject.getNormalizedQuery())
                .build();
        Map<RetrievalSource, List<Evidence>> secondRound =
                retrieveBySources(fallbackQuery, plan.getTopKConfig(), sources, true, true);

        Map<RetrievalSource, List<Evidence>> merged = new EnumMap<>(RetrievalSource.class);
        for (RetrievalSource source : sources) {
            List<Evidence> items = new ArrayList<>();
            items.addAll(firstRound.getOrDefault(source, List.of()));
            items.addAll(secondRound.getOrDefault(source, List.of()));
            merged.put(source, items);
        }

        return RetrievalResponse.builder()
                .route(route())
                .rewrittenQuery(queryObject.getRewrittenQuery())
                .evidences(merged)
                .meta(Map.of("fallback_to_modular_like_second_round", true))
                .build();
    }
}
