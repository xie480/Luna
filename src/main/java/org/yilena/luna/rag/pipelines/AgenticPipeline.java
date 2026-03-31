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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        List<RetrievalSource> sources = plan.getSources() == null || plan.getSources().isEmpty()
                ? resolveSources(request)
                : plan.getSources();
        long totalBudgetMs = resolveTimeoutMs(request);
        long deadline = System.currentTimeMillis() + totalBudgetMs;
        int maxSteps = Math.max(1, getRagProperties().getAgenticMaxSteps());
        int maxCalls = Math.max(1, getRagProperties().getAgenticMaxCalls());
        int maxTotalTopK = Math.max(1, getRagProperties().getAgenticMaxTotalTopK());

        List<ModelDrivenRagPlanner.AgentStage> stages = getModelDrivenRagPlanner()
                .planAgentStages(queryObject.getRewrittenQuery(), sources, maxSteps);

        Map<RetrievalSource, List<Evidence>> cumulative = initGrouped(sources);
        List<Map<String, Object>> stageMeta = new ArrayList<>();
        boolean timeoutReached = false;
        boolean evidenceSufficient = false;
        int callCount = 0;

        for (int i = 0; i < stages.size() && i < maxSteps && callCount < maxCalls; i++) {
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
            callCount++;

            cumulative = mergeBySource(cumulative, stageOutcome.grouped(), sources);

            Map<String, Object> singleStageMeta = new HashMap<>();
            singleStageMeta.put("stage_index", i + 1);
            singleStageMeta.put("objective", stage.getObjective());
            singleStageMeta.put("query", stage.getRewrittenQuery());
            singleStageMeta.put("sources", stageSources.stream().map(RetrievalSource::value).toList());
            singleStageMeta.put("evidence_count", totalEvidenceCount(stageOutcome.grouped()));
            singleStageMeta.put("timed_out_sources", stageOutcome.timedOutSources().stream().map(RetrievalSource::value).toList());
            stageMeta.add(singleStageMeta);

            evidenceSufficient = isEvidenceSufficient(cumulative, sources);
            if (evidenceSufficient) {
                break;
            }

            if (callCount < maxCalls) {
                List<RetrievalSource> missingSources = missingSources(cumulative, sources);
                if (!missingSources.isEmpty()) {
                    QueryObject supplementQuery = buildStageQuery(
                            queryObject,
                            stage.getRewrittenQuery() + "；补充检索缺失证据，重点覆盖：" + String.join(",", missingSources.stream().map(RetrievalSource::value).toList())
                    );
                    SourceRetrieveOutcome supplement = retrieveBySources(
                            supplementQuery,
                            plan.getTopKConfig(),
                            missingSources,
                            true,
                            true,
                            withTimeout(request, remainingMs(deadline)),
                            remainingMs(deadline)
                    );
                    callCount++;
                    cumulative = mergeBySource(cumulative, supplement.grouped(), sources);
                    Map<String, Object> supplementMeta = new HashMap<>();
                    supplementMeta.put("stage_index", i + 1);
                    supplementMeta.put("objective", "dynamic_supplement");
                    supplementMeta.put("query", supplementQuery.getRewrittenQuery());
                    supplementMeta.put("sources", missingSources.stream().map(RetrievalSource::value).toList());
                    supplementMeta.put("evidence_count", totalEvidenceCount(supplement.grouped()));
                    supplementMeta.put("timed_out_sources", supplement.timedOutSources().stream().map(RetrievalSource::value).toList());
                    stageMeta.add(supplementMeta);
                    evidenceSufficient = isEvidenceSufficient(cumulative, sources);
                    if (evidenceSufficient) {
                        break;
                    }
                }
            }
        }

        boolean overLimit = callCount >= maxCalls
                || stageMeta.size() >= maxSteps
                || estimateTotalTopK(plan, sources) > maxTotalTopK;

        if (timeoutReached || overLimit || !evidenceSufficient) {
            SourceRetrieveOutcome fallback = retrieveBySources(
                    queryObject,
                    plan.getTopKConfig(),
                    sources,
                    true,
                    true,
                    withTimeout(request, remainingMs(deadline)),
                    remainingMs(deadline)
            );
            Map<String, Object> fallbackMeta = new HashMap<>(fallback.meta());
            fallbackMeta.put("agentic_stage_count", stageMeta.size());
            fallbackMeta.put("agentic_stages", stageMeta);
            fallbackMeta.put("agentic_timeout_reached", timeoutReached);
            fallbackMeta.put("agentic_call_count", callCount);
            fallbackMeta.put("agentic_evidence_sufficient", evidenceSufficient);
            fallbackMeta.put("fallback_modular", true);
            fallbackMeta.put("fallback_reason", fallbackReason(timeoutReached, overLimit, evidenceSufficient));
            return RetrievalResponse.builder()
                    .route(route())
                    .rewrittenQuery(queryObject.getRewrittenQuery())
                    .evidences(fallback.grouped())
                    .meta(fallbackMeta)
                    .build();
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
        meta.put("agentic_call_count", callCount);
        meta.put("agentic_evidence_sufficient", evidenceSufficient);
        meta.put("fallback_modular", false);

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
        List<Double> stageEmbedding = parseEmbedding(embeddingProvider.embedding(stageRewrittenQuery));
        return original.toBuilder()
                .rewrittenQuery(stageRewrittenQuery)
                .embedding(stageEmbedding.isEmpty() ? original.getEmbedding() : stageEmbedding)
                .build();
    }

    private List<Double> parseEmbedding(String rawEmbedding) {
        if (rawEmbedding == null || rawEmbedding.isBlank()) {
            return List.of();
        }
        String cleaned = rawEmbedding.trim();
        if (cleaned.startsWith("[")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.endsWith("]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isBlank()) {
            return List.of();
        }
        try {
            return java.util.Arrays.stream(cleaned.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Double::parseDouble)
                    .toList();
        } catch (Exception ignore) {
            return List.of();
        }
    }

    private boolean isEvidenceSufficient(Map<RetrievalSource, List<Evidence>> grouped, List<RetrievalSource> sources) {
        int minimumEvidence = Math.max(1, getRagProperties().getAgenticMinEvidence());
        long total = totalEvidenceCount(grouped);
        if (total < minimumEvidence) {
            return false;
        }
        for (RetrievalSource source : sources) {
            if (grouped.getOrDefault(source, List.of()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private List<RetrievalSource> missingSources(Map<RetrievalSource, List<Evidence>> grouped, List<RetrievalSource> sources) {
        return sources.stream()
                .filter(source -> grouped.getOrDefault(source, List.of()).isEmpty())
                .toList();
    }

    private int estimateTotalTopK(RoutePlan plan, List<RetrievalSource> sources) {
        if (plan == null || plan.getTopKConfig() == null) {
            return sources.size() * 3;
        }
        return sources.stream()
                .map(source -> plan.getTopKConfig().getOrDefault(source, 3))
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    private String fallbackReason(boolean timeoutReached, boolean overLimit, boolean evidenceSufficient) {
        Set<String> reasons = new LinkedHashSet<>();
        if (timeoutReached) {
            reasons.add("timeout");
        }
        if (overLimit) {
            reasons.add("limit_reached");
        }
        if (!evidenceSufficient) {
            reasons.add("insufficient_evidence");
        }
        return String.join(",", reasons);
    }
}
