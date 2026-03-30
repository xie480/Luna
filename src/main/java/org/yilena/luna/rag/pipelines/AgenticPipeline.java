package org.yilena.luna.rag.pipelines; // define package

import org.springframework.stereotype.Component; // import dependency
import org.yilena.luna.rag.adapters.EmbeddingProvider; // import dependency
import org.yilena.luna.rag.config.RagProperties; // import dependency
import org.yilena.luna.rag.fusion.EvidenceFusionService; // import dependency
import org.yilena.luna.rag.models.Evidence; // import dependency
import org.yilena.luna.rag.models.QueryObject; // import dependency
import org.yilena.luna.rag.models.RetrievalRequest; // import dependency
import org.yilena.luna.rag.models.RetrievalResponse; // import dependency
import org.yilena.luna.rag.models.RetrievalRoute; // import dependency
import org.yilena.luna.rag.models.RetrievalSource; // import dependency
import org.yilena.luna.rag.models.RoutePlan; // import dependency
import org.yilena.luna.rag.planner.ModelDrivenRagPlanner; // import dependency
import org.yilena.luna.rag.rankers.EvidenceCompressor; // import dependency
import org.yilena.luna.rag.rankers.EvidenceDeduplicator; // import dependency
import org.yilena.luna.rag.rankers.EvidenceReranker; // import dependency
import org.yilena.luna.rag.retrievers.BaseRetriever; // import dependency

import java.util.ArrayList; // import dependency
import java.util.HashMap; // import dependency
import java.util.List; // import dependency
import java.util.Map; // import dependency

@Component // declare annotation
public class AgenticPipeline extends AbstractRetrievalPipeline { // define class

    private final EmbeddingProvider embeddingProvider; // business logic

    public AgenticPipeline( // business logic
            List<BaseRetriever> retrievers, // business logic
            EvidenceReranker evidenceReranker, // business logic
            EvidenceDeduplicator evidenceDeduplicator, // business logic
            EvidenceCompressor evidenceCompressor, // business logic
            RagProperties ragProperties, // business logic
            ModelDrivenRagPlanner modelDrivenRagPlanner, // business logic
            EvidenceFusionService evidenceFusionService, // business logic
            EmbeddingProvider embeddingProvider // business logic
    ) { // block start
        super(retrievers.stream().collect(java.util.stream.Collectors.toMap(BaseRetriever::source, it -> it)), // enum or const item
                evidenceReranker, evidenceDeduplicator, evidenceCompressor, // enum or const item
                ragProperties, modelDrivenRagPlanner, evidenceFusionService); // enum or const item
        this.embeddingProvider = embeddingProvider; // assignment or init
    } // block end

    @Override // declare annotation
    public RetrievalRoute route() { // method definition
        return RetrievalRoute.AGENTIC; // return result
    } // block end

    @Override // declare annotation
    public RetrievalResponse execute(QueryObject queryObject, RoutePlan plan, RetrievalRequest request) { // method definition
        List<RetrievalSource> sources = resolveSources(request); // assignment or init
        long totalBudgetMs = resolveTimeoutMs(request); // assignment or init
        long deadline = System.currentTimeMillis() + totalBudgetMs; // assignment or init

        List<ModelDrivenRagPlanner.AgentStage> stages = getModelDrivenRagPlanner() // assignment or init
                .planAgentStages(queryObject.getRewrittenQuery(), sources, 3); // business logic

        Map<RetrievalSource, List<Evidence>> cumulative = initGrouped(sources); // assignment or init
        List<Map<String, Object>> stageMeta = new ArrayList<>(); // assignment or init
        boolean timeoutReached = false; // assignment or init

        for (int i = 0; i < stages.size(); i++) { // loop logic
            long remaining = remainingMs(deadline); // assignment or init
            if (remaining < 120) { // branch logic
                timeoutReached = true; // assignment or init
                break; // enum or const item
            } // block end

            ModelDrivenRagPlanner.AgentStage stage = stages.get(i); // assignment or init
            QueryObject stageQuery = buildStageQuery(queryObject, stage.getRewrittenQuery()); // assignment or init
            RetrievalRequest stageRequest = withTimeout(request, remaining); // assignment or init
            List<RetrievalSource> stageSources = stage.getSources() == null || stage.getSources().isEmpty() // assignment or init
                    ? sources // business logic
                    : stage.getSources(); // business logic

            SourceRetrieveOutcome stageOutcome = retrieveBySources( // assignment or init
                    stageQuery, // enum or const item
                    plan.getTopKConfig(), // business logic
                    stageSources, // enum or const item
                    true, // enum or const item
                    true, // enum or const item
                    stageRequest, // enum or const item
                    remaining // business logic
            ); // business logic

            cumulative = mergeBySource(cumulative, stageOutcome.grouped(), sources); // assignment or init

            Map<String, Object> singleStageMeta = new HashMap<>(); // assignment or init
            singleStageMeta.put("stage_index", i + 1); // business logic
            singleStageMeta.put("objective", stage.getObjective()); // business logic
            singleStageMeta.put("query", stage.getRewrittenQuery()); // business logic
            singleStageMeta.put("sources", stageSources.stream().map(RetrievalSource::value).toList()); // business logic
            singleStageMeta.put("evidence_count", totalEvidenceCount(stageOutcome.grouped())); // business logic
            singleStageMeta.put("timed_out_sources", stageOutcome.timedOutSources().stream().map(RetrievalSource::value).toList()); // business logic
            stageMeta.add(singleStageMeta); // business logic
        } // block end

        EvidenceFusionService.FusionResult finalFusion = getEvidenceFusionService().fuse( // assignment or init
                queryObject.getRewrittenQuery(), // business logic
                cumulative, // enum or const item
                plan.getTopKConfig(), // business logic
                sources, // enum or const item
                true // business logic
        ); // business logic

        Map<String, Object> meta = new HashMap<>(finalFusion.meta()); // assignment or init
        meta.put("agentic_stage_count", stageMeta.size()); // business logic
        meta.put("agentic_stages", stageMeta); // business logic
        meta.put("agentic_timeout_reached", timeoutReached); // business logic

        return RetrievalResponse.builder() // return result
                .route(route()) // business logic
                .rewrittenQuery(queryObject.getRewrittenQuery()) // business logic
                .evidences(finalFusion.grouped()) // business logic
                .meta(meta) // business logic
                .build(); // business logic
    } // block end

    private QueryObject buildStageQuery(QueryObject original, String stageRewrittenQuery) { // method definition
        if (stageRewrittenQuery == null || stageRewrittenQuery.isBlank() // branch logic
                || stageRewrittenQuery.equals(original.getRewrittenQuery())) { // block start
            return original; // return result
        } // block end
        String stageEmbedding = embeddingProvider.embedding(stageRewrittenQuery); // assignment or init
        return original.toBuilder() // return result
                .rewrittenQuery(stageRewrittenQuery) // business logic
                .embedding(stageEmbedding == null || stageEmbedding.isBlank() ? original.getEmbedding() : stageEmbedding) // assignment or init
                .build(); // business logic
    } // block end
} // block end
