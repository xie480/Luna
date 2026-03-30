package org.yilena.luna.rag.pipelines;

import org.springframework.stereotype.Component;
import org.yilena.luna.rag.adapters.EmbeddingProvider;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgenticPipeline extends AbstractRetrievalPipeline {

    private final EmbeddingProvider embeddingProvider;

    public AgenticPipeline(
            List<BaseRetriever> retrievers,
            EvidenceReranker evidenceReranker,
            EvidenceDeduplicator evidenceDeduplicator,
            EvidenceCompressor evidenceCompressor,
            RagProperties ragProperties,
            ModelDrivenRagPlanner modelDrivenRagPlanner,
            EvidenceFusionService evidenceFusionService,
            EmbeddingProvider embeddingProvider
    ) {
        super(retrievers.stream().collect(java.util.stream.Collectors.toMap(BaseRetriever::source, it -> it)),
                evidenceReranker, evidenceDeduplicator, evidenceCompressor,
                ragProperties, modelDrivenRagPlanner, evidenceFusionService);
        this.embeddingProvider = embeddingProvider;
    }

    @Override
    public RetrievalRoute route() {
        return RetrievalRoute.AGENTIC;
    }

    @Override
    public RetrievalResponse execute(QueryObject queryObject, RoutePlan plan, RetrievalRequest request) {
        List<RetrievalSource> sources = resolveSources(request);
        long totalBudgetMs = resolveTimeoutMs(request);
        long deadline = System.currentTimeMillis() + totalBudgetMs;

        List<ModelDrivenRagPlanner.AgentStage> stages = getModelDrivenRagPlanner()
                .planAgentStages(queryObject.getRewrittenQuery(), sources, 3);

        Map<RetrievalSource, List<Evidence>> cumulative = initGrouped(sources);
        List<Map<String, Object>> stageMeta = new ArrayList<>();
        boolean timeoutReached = false;

        for (int i = 0; i < stages.size(); i++) {
            long remaining = remainingMs(deadline);
            if (remaining < 120) {
                timeoutReached = true;
                break;
            }

            ModelDrivenRagPlanner.AgentStage stage = stages.get(i);
            QueryObject stageQuery = buildStageQuery(queryObject, stage.getRewrittenQuery());
            RetrievalRequest stageRequest = withTimeout(request, remaining);
            List<RetrievalSource> stageSources = stage.getSources() == null || stage.getSources().isEmpty()
                    ? sources
                    : stage.getSources();

            SourceRetrieveOutcome stageOutcome = retrieveBySources(
                    stageQuery,
                    plan.getTopKConfig(),
                    stageSources,
                    true,
                    true,
                    stageRequest,
                    remaining
            );

            cumulative = mergeBySource(cumulative, stageOutcome.grouped(), sources);

            Map<String, Object> singleStageMeta = new HashMap<>();
            singleStageMeta.put("stage_index", i + 1);
            singleStageMeta.put("objective", stage.getObjective());
            singleStageMeta.put("query", stage.getRewrittenQuery());
            singleStageMeta.put("sources", stageSources.stream().map(RetrievalSource::value).toList());
            singleStageMeta.put("evidence_count", totalEvidenceCount(stageOutcome.grouped()));
            singleStageMeta.put("timed_out_sources", stageOutcome.timedOutSources().stream().map(RetrievalSource::value).toList());
            stageMeta.add(singleStageMeta);
        }

        EvidenceFusionService.FusionResult finalFusion = getEvidenceFusionService().fuse(
                queryObject.getRewrittenQuery(),
                cumulative,
                plan.getTopKConfig(),
                sources,
                true
        );

        Map<String, Object> meta = new HashMap<>(finalFusion.meta());
        meta.put("agentic_stage_count", stageMeta.size());
        meta.put("agentic_stages", stageMeta);
        meta.put("agentic_timeout_reached", timeoutReached);

        return RetrievalResponse.builder()
                .route(route())
                .rewrittenQuery(queryObject.getRewrittenQuery())
                .evidences(finalFusion.grouped())
                .meta(meta)
                .build();
    }

    private QueryObject buildStageQuery(QueryObject original, String stageRewrittenQuery) {
        if (stageRewrittenQuery == null || stageRewrittenQuery.isBlank()
                || stageRewrittenQuery.equals(original.getRewrittenQuery())) {
            return original;
        }
        String stageEmbedding = embeddingProvider.embedding(stageRewrittenQuery);
        return original.toBuilder()
                .rewrittenQuery(stageRewrittenQuery)
                .embedding(stageEmbedding == null || stageEmbedding.isBlank() ? original.getEmbedding() : stageEmbedding)
                .build();
    }
}
