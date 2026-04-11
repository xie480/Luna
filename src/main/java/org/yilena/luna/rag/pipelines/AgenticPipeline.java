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
import org.yilena.luna.rag.support.SemanticTextService;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
/**
 * 该流水线面向复杂推理场景，通过多阶段检索逐步补全证据，并在预算不足或证据不足时回退到 modular 结果。
 */
public class AgenticPipeline extends AbstractRetrievalPipeline {

    /**
     * Embedding 适配器，用于为阶段改写查询重新生成向量。
     */
    private final EmbeddingProvider embeddingProvider;
    /**
     * 语义文本服务，用于判断阶段目标与最终证据之间的覆盖程度。
     */
    private final SemanticTextService semanticTextService;

    public AgenticPipeline(
            List<BaseRetriever> retrievers,
            EvidenceReranker evidenceReranker,
            EvidenceDeduplicator evidenceDeduplicator,
            EvidenceCompressor evidenceCompressor,
            RagProperties ragProperties,
            ModelDrivenRagPlanner modelDrivenRagPlanner,
            EvidenceFusionService evidenceFusionService,
            EmbeddingProvider embeddingProvider,
            SemanticTextService semanticTextService
    ) {
        super(retrievers.stream().collect(java.util.stream.Collectors.toMap(BaseRetriever::source, it -> it)),
                evidenceReranker, evidenceDeduplicator, evidenceCompressor,
                ragProperties, modelDrivenRagPlanner, evidenceFusionService);
        this.embeddingProvider = embeddingProvider;
        this.semanticTextService = semanticTextService;
    }

    @Override
    public RetrievalRoute route() {
        return RetrievalRoute.AGENTIC;
    }

    /**
     * 依次执行阶段规划、阶段检索、充分性评估和必要时的 modular 回退，输出最终检索结果。
     */
    @Override
    public RetrievalResponse execute(QueryObject queryObject, RoutePlan plan, RetrievalRequest request) {
        /**
         * 先准备来源范围、总预算和阶段上限，为后续多阶段检索设定边界。
         */
        List<RetrievalSource> sources = plan.getSources() == null || plan.getSources().isEmpty()
                ? resolveSources(request)
                : plan.getSources();
        long totalBudgetMs = resolveTimeoutMs(request);
        long deadline = System.currentTimeMillis() + totalBudgetMs;
        int maxSteps = Math.max(1, getRagProperties().getAgenticMaxSteps());
        int maxCalls = Math.max(1, getRagProperties().getAgenticMaxCalls());
        int maxTotalTopK = Math.max(1, getRagProperties().getAgenticMaxTotalTopK());

        /**
         * 再让规划器把复杂问题拆成多个检索阶段，后续按阶段逐步补齐证据。
         */
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
        SufficiencyCheck latestSufficiency = SufficiencyCheck.empty();

        /**
         * 按阶段执行检索，并在每一轮后累计证据、记录阶段元信息和评估证据充分性。
         */
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
                    plan.isNeedsRerank(),
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

            latestSufficiency = evaluateEvidenceSufficiency(
                    cumulative,
                    sources,
                    stageMeta,
                    queryObject.getRewrittenQuery()
            );
            evidenceSufficient = latestSufficiency.sufficient();
            if (evidenceSufficient) {
                break;
            }

            /**
             * 当前阶段结束后如果仍有缺失来源，则尝试补发一次定向补充检索。
             */
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
                            plan.isNeedsRerank(),
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
                    latestSufficiency = evaluateEvidenceSufficiency(
                            cumulative,
                            sources,
                            stageMeta,
                            queryObject.getRewrittenQuery()
                    );
                    evidenceSufficient = latestSufficiency.sufficient();
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

        /**
         * 当达到时限、步数或证据仍不足时，统一回退到 modular 风格的最终融合结果，保证始终有可用输出。
         */
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
                            plan.isNeedsRerank(),
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
                            plan.isNeedsRerank(),
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
                        plan.isNeedsRerank(),
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
            fallbackMeta.put("agentic_semantic_stage_coverage", latestSufficiency.stageCoverage());
            fallbackMeta.put("agentic_semantic_query_relevance", latestSufficiency.queryRelevance());
            fallbackMeta.put("agentic_semantic_sufficient", latestSufficiency.semanticSatisfied());
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
                    .evidenceRoleGroups(buildEvidenceRoleGroups(ensureAllEvidenceBuckets(fallbackGrouped)))
                    .meta(fallbackMeta)
                    .build();
        }

        /**
         * 证据充分时执行最终融合，输出 agentic 路由的完整结果。
         */
        EvidenceFusionService.FusionResult finalFusion = getEvidenceFusionService().fuse(
                queryObject.getRewrittenQuery(),
                cumulative,
                baseTopK,
                sources,
                plan.isNeedsRerank(),
                true
        );

        Map<String, Object> meta = new HashMap<>(finalFusion.meta());
        meta.put("agentic_stage_count", stageMeta.size());
        meta.put("agentic_stages", stageMeta);
        meta.put("agentic_timeout_reached", timeoutReached);
        meta.put("agentic_call_count", callCount);
        meta.put("agentic_evidence_sufficient", evidenceSufficient);
        meta.put("agentic_semantic_stage_coverage", latestSufficiency.stageCoverage());
        meta.put("agentic_semantic_query_relevance", latestSufficiency.queryRelevance());
        meta.put("agentic_semantic_sufficient", latestSufficiency.semanticSatisfied());
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
                .evidenceRoleGroups(buildEvidenceRoleGroups(ensureAllEvidenceBuckets(finalFusion.grouped())))
                .meta(meta)
                .build();
    }

    /**
     * 为阶段改写查询补充新的 embedding，避免沿用旧向量导致阶段召回偏移。
     */
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

    /**
     * 从证据覆盖数、来源覆盖和语义相似度三个维度判断当前阶段结果是否已经足够支撑回答。
     */
    private SufficiencyCheck evaluateEvidenceSufficiency(
            Map<RetrievalSource, List<Evidence>> grouped,
            List<RetrievalSource> sources,
            List<Map<String, Object>> stageMeta,
            String rewrittenQuery
    ) {
        int minimumEvidence = Math.max(1, getRagProperties().getAgenticMinEvidence());
        long total = totalEvidenceCount(grouped);
        if (total < minimumEvidence) {
            return SufficiencyCheck.insufficient();
        }
        for (RetrievalSource source : sources) {
            if (grouped.getOrDefault(source, List.of()).isEmpty()) {
                return SufficiencyCheck.insufficient();
            }
        }

        List<Evidence> allEvidence = flatten(grouped);
        List<String> objectives = stageMeta.stream()
                .map(meta -> String.valueOf(meta.getOrDefault("objective", "")))
                .filter(v -> v != null && !v.isBlank() && !"dynamic_supplement".equalsIgnoreCase(v))
                .distinct()
                .toList();

        Map<String, List<Double>> embeddingCache = new HashMap<>();
        int covered = 0;
        for (String objective : objectives) {
            double maxSimilarity = maxSimilarity(objective, allEvidence, embeddingCache);
            if (maxSimilarity >= getRagProperties().getAgenticSemanticSufficiencyThreshold()) {
                covered++;
            }
        }
        double stageCoverage = objectives.isEmpty() ? 1.0 : (double) covered / (double) objectives.size();
        double queryRelevance = maxSimilarity(rewrittenQuery, allEvidence, embeddingCache);
        boolean semanticSatisfied = stageCoverage >= getRagProperties().getAgenticSemanticStageCoverageRatio()
                && queryRelevance >= getRagProperties().getAgenticSemanticSufficiencyThreshold();
        return new SufficiencyCheck(semanticSatisfied, stageCoverage, queryRelevance, semanticSatisfied);
    }

    private double maxSimilarity(String target, List<Evidence> evidences, Map<String, List<Double>> embeddingCache) {
        if (target == null || target.isBlank() || evidences == null || evidences.isEmpty()) {
            return 0.0;
        }
        double max = 0.0;
        for (Evidence evidence : evidences) {
            if (evidence == null || evidence.getContent() == null || evidence.getContent().isBlank()) {
                continue;
            }
            max = Math.max(max, semanticTextService.similarity(target, evidence.getContent(), embeddingCache));
        }
        return max;
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

    /**
     * 该记录用于承载 agentic 证据充分性评估结果。
     */
    private record SufficiencyCheck(
            boolean sufficient,
            double stageCoverage,
            double queryRelevance,
            boolean semanticSatisfied
    ) {
        private static SufficiencyCheck empty() {
            return new SufficiencyCheck(false, 0.0, 0.0, false);
        }

        private static SufficiencyCheck insufficient() {
            return new SufficiencyCheck(false, 0.0, 0.0, false);
        }
    }
}
