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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        boolean totalTopKExceeded = false;
        int callCount = 0;
        int cumulativeRequestedTopK = 0;
        Map<RetrievalSource, Integer> baseTopK = normalizeTopKConfig(plan.getTopKConfig(), sources);

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

            int stageBudget = maxTotalTopK - cumulativeRequestedTopK;
            Map<RetrievalSource, Integer> stageTopKConfig = allocateTopKByBudget(baseTopK, stageSources, stageBudget);
            if (stageTopKConfig.isEmpty()) {
                totalTopKExceeded = true;
                break;
            }
            int stageRequestedTopK = sumTopK(stageTopKConfig, stageSources);
            cumulativeRequestedTopK += stageRequestedTopK;

            SourceRetrieveOutcome stageOutcome = retrieveBySources(
                    stageQuery,
                    stageTopKConfig,
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
            singleStageMeta.put("requested_top_k", stageRequestedTopK);
            singleStageMeta.put("cumulative_requested_top_k", cumulativeRequestedTopK);
            singleStageMeta.put("timed_out_sources", stageOutcome.timedOutSources().stream().map(RetrievalSource::value).toList());
            stageMeta.add(singleStageMeta);

            evidenceSufficient = isEvidenceSufficient(cumulative, sources);
            if (evidenceSufficient) {
                break;
            }

            if (callCount < maxCalls) {
                List<RetrievalSource> missingSources = missingSources(cumulative, sources);
                if (!missingSources.isEmpty()) {
                    int supplementBudget = maxTotalTopK - cumulativeRequestedTopK;
                    Map<RetrievalSource, Integer> supplementTopKConfig = allocateTopKByBudget(baseTopK, missingSources, supplementBudget);
                    if (supplementTopKConfig.isEmpty()) {
                        totalTopKExceeded = true;
                        break;
                    }
                    int supplementRequestedTopK = sumTopK(supplementTopKConfig, missingSources);
                    cumulativeRequestedTopK += supplementRequestedTopK;
                    QueryObject supplementQuery = buildStageQuery(
                            queryObject,
                            stage.getRewrittenQuery() + " ; supplement missing evidence for: "
                                    + String.join(",", missingSources.stream().map(RetrievalSource::value).toList())
                    );
                    SourceRetrieveOutcome supplement = retrieveBySources(
                            supplementQuery,
                            supplementTopKConfig,
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
                    supplementMeta.put("requested_top_k", supplementRequestedTopK);
                    supplementMeta.put("cumulative_requested_top_k", cumulativeRequestedTopK);
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
                || totalTopKExceeded
                || cumulativeRequestedTopK >= maxTotalTopK;

        if (timeoutReached || overLimit || !evidenceSufficient) {
            int fallbackBudget = maxTotalTopK - cumulativeRequestedTopK;
            Map<RetrievalSource, List<Evidence>> fallbackGrouped;
            Map<String, Object> fallbackMeta;
            if (fallbackBudget > 0 && !timeoutReached) {
                Map<RetrievalSource, Integer> fallbackTopK = allocateTopKByBudget(baseTopK, sources, fallbackBudget);
                if (!fallbackTopK.isEmpty()) {
                    SourceRetrieveOutcome fallback = retrieveBySources(
                            queryObject,
                            fallbackTopK,
                            sources,
                            true,
                            true,
                            withTimeout(request, remainingMs(deadline)),
                            remainingMs(deadline)
                    );
                    cumulativeRequestedTopK += sumTopK(fallbackTopK, sources);
                    fallbackGrouped = fallback.grouped();
                    fallbackMeta = new HashMap<>(fallback.meta());
                } else {
                    EvidenceFusionService.FusionResult fallbackFusion = getEvidenceFusionService().fuse(
                            queryObject.getRewrittenQuery(),
                            cumulative,
                            baseTopK,
                            sources,
                            true
                    );
                    fallbackGrouped = fallbackFusion.grouped();
                    fallbackMeta = new HashMap<>(fallbackFusion.meta());
                }
            } else {
                EvidenceFusionService.FusionResult fallbackFusion = getEvidenceFusionService().fuse(
                        queryObject.getRewrittenQuery(),
                        cumulative,
                        baseTopK,
                        sources,
                        true
                );
                fallbackGrouped = fallbackFusion.grouped();
                fallbackMeta = new HashMap<>(fallbackFusion.meta());
            }
            fallbackMeta.put("agentic_stage_count", stageMeta.size());
            fallbackMeta.put("agentic_stages", stageMeta);
            fallbackMeta.put("agentic_timeout_reached", timeoutReached);
            fallbackMeta.put("agentic_call_count", callCount);
            fallbackMeta.put("agentic_evidence_sufficient", evidenceSufficient);
            fallbackMeta.put("agentic_max_total_top_k", maxTotalTopK);
            fallbackMeta.put("agentic_total_requested_top_k", cumulativeRequestedTopK);
            fallbackMeta.put("fallback_modular", true);
            fallbackMeta.put("fallback_reason", fallbackReason(timeoutReached, overLimit, evidenceSufficient));
            if (isDebugEnabled(request)) {
                fallbackMeta.put("agentic_debug", Map.of(
                        "max_steps", maxSteps,
                        "max_calls", maxCalls,
                        "max_total_top_k", maxTotalTopK,
                        "total_requested_top_k", cumulativeRequestedTopK,
                        "top_k_exceeded", totalTopKExceeded
                ));
            }
            return RetrievalResponse.builder()
                    .route(RetrievalRoute.MODULAR)
                    .rewrittenQuery(queryObject.getRewrittenQuery())
                    .evidences(ensureAllEvidenceBuckets(fallbackGrouped))
                    .meta(fallbackMeta)
                    .build();
        }

        EvidenceFusionService.FusionResult finalFusion = getEvidenceFusionService().fuse(
                queryObject.getRewrittenQuery(),
                cumulative,
                baseTopK,
                sources,
                true
        );

        Map<String, Object> meta = new HashMap<>(finalFusion.meta());
        meta.put("agentic_stage_count", stageMeta.size());
        meta.put("agentic_stages", stageMeta);
        meta.put("agentic_timeout_reached", timeoutReached);
        meta.put("agentic_call_count", callCount);
        meta.put("agentic_evidence_sufficient", evidenceSufficient);
        meta.put("agentic_max_total_top_k", maxTotalTopK);
        meta.put("agentic_total_requested_top_k", cumulativeRequestedTopK);
        meta.put("fallback_modular", false);
        if (isDebugEnabled(request)) {
            meta.put("agentic_debug", Map.of(
                    "max_steps", maxSteps,
                    "max_calls", maxCalls,
                    "max_total_top_k", maxTotalTopK,
                    "total_requested_top_k", cumulativeRequestedTopK,
                    "top_k_exceeded", totalTopKExceeded
            ));
        }

        return RetrievalResponse.builder()
                .route(route())
                .rewrittenQuery(queryObject.getRewrittenQuery())
                .evidences(ensureAllEvidenceBuckets(finalFusion.grouped()))
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

    private Map<RetrievalSource, Integer> allocateTopKByBudget(
            Map<RetrievalSource, Integer> baseTopK,
            List<RetrievalSource> targetSources,
            int remainingBudget
    ) {
        if (remainingBudget <= 0 || targetSources == null || targetSources.isEmpty()) {
            return Map.of();
        }
        Map<RetrievalSource, Integer> allocated = new EnumMap<>(RetrievalSource.class);
        int remain = remainingBudget;
        for (RetrievalSource source : targetSources) {
            if (remain <= 0) {
                break;
            }
            int desired = Math.max(1, baseTopK.getOrDefault(source, 3));
            int granted = Math.min(desired, remain);
            if (granted > 0) {
                allocated.put(source, granted);
                remain -= granted;
            }
        }
        return allocated;
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
