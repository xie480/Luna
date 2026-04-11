package org.yilena.luna.rag.pipelines;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.fusion.EvidenceFusionService;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.EvidenceRole;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalOptions;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.planner.ModelDrivenRagPlanner;
import org.yilena.luna.rag.rankers.EvidenceCompressor;
import org.yilena.luna.rag.rankers.EvidenceDeduplicator;
import org.yilena.luna.rag.rankers.EvidenceReranker;
import org.yilena.luna.rag.retrievers.BaseRetriever;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
/**
 * 该抽象流水线封装了多来源检索、后处理、跨源融合和调试元信息组装的通用逻辑。
 */
public abstract class AbstractRetrievalPipeline implements RetrievalPipeline {

    private final Map<RetrievalSource, BaseRetriever> retrieverMap;
    private final EvidenceReranker evidenceReranker;
    private final EvidenceDeduplicator evidenceDeduplicator;
    private final EvidenceCompressor evidenceCompressor;
    private final RagProperties ragProperties;
    private final ModelDrivenRagPlanner modelDrivenRagPlanner;
    private final EvidenceFusionService evidenceFusionService;

    /**
     * 按指定来源并发检索证据，并统一执行来源内后处理与可选的跨源融合。
     */
    protected SourceRetrieveOutcome retrieveBySources(
            QueryObject queryObject,
            Map<RetrievalSource, Integer> topKConfig,
            List<RetrievalSource> sources,
            boolean rerank,
            boolean compress,
            boolean enableCrossSourceFusion,
            RetrievalRequest request,
            long timeoutMs
    ) {
        /**
         * 先归一化目标来源和 topK 预算，并计算本轮检索的总时间预算。
         */
        List<RetrievalSource> targetSources = sources == null || sources.isEmpty() ? RetrievalSource.all() : sources;
        Map<RetrievalSource, Integer> effectiveTopK = normalizeTopKConfig(topKConfig, targetSources);
        long budgetMs = timeoutMs > 0 ? timeoutMs : resolveTimeoutMs(request);
        long deadline = System.currentTimeMillis() + budgetMs;

        /**
         * 并发发起各来源检索，尽量在统一时限内拿到更多候选。
         */
        Map<RetrievalSource, CompletableFuture<List<Evidence>>> retrievalFutures = new EnumMap<>(RetrievalSource.class);
        for (RetrievalSource source : targetSources) {
            int topK = effectiveTopK.getOrDefault(source, 3);
            retrievalFutures.put(source, CompletableFuture.supplyAsync(() -> retrieveSingleSource(source, queryObject, topK)));
        }

        long remaining = remainingMs(deadline);
        if (!retrievalFutures.isEmpty() && remaining > 0) {
            CompletableFuture<?>[] all = retrievalFutures.values().toArray(CompletableFuture[]::new);
            try {
                CompletableFuture.allOf(all).get(remaining, TimeUnit.MILLISECONDS);
            } catch (Exception ignore) {
        // 允许保留部分成功结果，后续只汇总已完成的异步检索任务。
            }
        }

        /**
         * 收集各来源已完成结果，并在来源内执行去重、重排和压缩等后处理。
         */
        Map<RetrievalSource, List<Evidence>> processedBySource = new EnumMap<>(RetrievalSource.class);
        Map<RetrievalSource, Integer> rawCountBySource = new EnumMap<>(RetrievalSource.class);
        List<RetrievalSource> timedOutSources = new ArrayList<>();
        for (RetrievalSource source : targetSources) {
            CompletableFuture<List<Evidence>> future = retrievalFutures.get(source);
            List<Evidence> sourceEvidences;
            if (future != null && future.isDone() && !future.isCompletedExceptionally()) {
                sourceEvidences = future.getNow(List.of());
            } else {
                timedOutSources.add(source);
                if (future != null) {
                    future.cancel(true);
                }
                sourceEvidences = List.of();
            }
            rawCountBySource.put(source, sourceEvidences.size());
            int topK = effectiveTopK.getOrDefault(source, 3);
            boolean sourceLevelDedup = !enableCrossSourceFusion;
            boolean sourceLevelCompress = !enableCrossSourceFusion && compress;
            sourceEvidences = processSourceEvidence(
                    queryObject,
                    source,
                    sourceEvidences,
                    topK,
                    sourceLevelDedup,
                    rerank,
                    sourceLevelCompress
            );
            processedBySource.put(source, sourceEvidences);
        }

        /**
         * 开启跨源融合时执行全局重排和再分配，否则直接保留来源内处理后的结果。
         */
        Map<RetrievalSource, List<Evidence>> grouped;
        List<RetrievalSource> hitSources;
        Map<String, Object> meta;
        if (enableCrossSourceFusion) {
            boolean preferMidModel = shouldPreferMidModel(queryObject, processedBySource);
            EvidenceFusionService.FusionResult fusionResult = evidenceFusionService.fuse(
                    queryObject.getRewrittenQuery(),
                    processedBySource,
                    effectiveTopK,
                    targetSources,
                    rerank,
                    preferMidModel
            );
            grouped = fusionResult.grouped();
            hitSources = fusionResult.hitSources();
            meta = new HashMap<>(fusionResult.meta());
            PostFusionOutcome postFusionOutcome = postFusionProcess(grouped, effectiveTopK, targetSources, compress);
            grouped = postFusionOutcome.grouped();
            hitSources = postFusionOutcome.hitSources();
            meta.putAll(postFusionOutcome.meta());
        } else {
            grouped = new EnumMap<>(RetrievalSource.class);
            grouped.putAll(processedBySource);
            hitSources = targetSources.stream()
                    .filter(source -> !processedBySource.getOrDefault(source, List.of()).isEmpty())
                    .toList();
            meta = new HashMap<>();
            meta.put("hit_sources", hitSources.stream().map(RetrievalSource::value).toList());
        }

        /**
         * 最后补充超时、topK 和调试元数据，供上层日志与排障使用。
         */
        meta.put("cross_source_fusion", enableCrossSourceFusion);
        meta.put("timed_out_sources", timedOutSources.stream().map(RetrievalSource::value).toList());
        meta.put("timeout_ms", budgetMs);
        if (isDebugEnabled(request)) {
            Map<String, Integer> rawCounts = new LinkedHashMap<>();
            Map<String, Integer> processedCounts = new LinkedHashMap<>();
            Map<String, Integer> topKPerSource = new LinkedHashMap<>();
            for (RetrievalSource source : targetSources) {
                String key = source.value();
                rawCounts.put(key, rawCountBySource.getOrDefault(source, 0));
                processedCounts.put(key, processedBySource.getOrDefault(source, List.of()).size());
                topKPerSource.put(key, effectiveTopK.getOrDefault(source, 3));
            }
            Map<String, Object> debug = new LinkedHashMap<>();
            debug.put("pipeline", getClass().getSimpleName());
            debug.put("query", queryObject.getRewrittenQuery());
            debug.put("target_sources", targetSources.stream().map(RetrievalSource::value).toList());
            debug.put("top_k_per_source", topKPerSource);
            debug.put("requested_top_k_total", sumTopK(effectiveTopK, targetSources));
            debug.put("raw_counts_by_source", rawCounts);
            debug.put("processed_counts_by_source", processedCounts);
            debug.put("rerank_enabled", rerank);
            debug.put("compress_enabled", compress);
            debug.put("cross_source_fusion", enableCrossSourceFusion);
            debug.put("post_fusion_processing", enableCrossSourceFusion);
            meta.put("debug", debug);
        }
        return new SourceRetrieveOutcome(grouped, timedOutSources, hitSources, meta);
    }

    private List<Evidence> retrieveSingleSource(RetrievalSource source, QueryObject queryObject, int topK) {
        BaseRetriever retriever = retrieverMap.get(source);
        if (retriever == null) {
            return Collections.emptyList();
        }
        try {
            List<Evidence> evidences = retriever.retrieve(queryObject, topK, queryObject.getPossibleFilters());
            return evidences == null ? List.of() : evidences;
        } catch (Exception ex) {
            log.warn("retrieve source failed, source={}, message={}", source.value(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * 对单个来源的证据执行规划驱动的去重、重排和压缩。
     */
    private List<Evidence> processSourceEvidence(
            QueryObject queryObject,
            RetrievalSource source,
            List<Evidence> evidences,
            int defaultTopK,
            boolean allowDeduplicate,
            boolean allowRerank,
            boolean allowCompress
    ) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }

        /**
         * 先生成来源后处理计划，再按计划依次执行去重、重排和压缩。
         */
        ModelDrivenRagPlanner.SourceProcessPlan plan = modelDrivenRagPlanner.planSourceProcessing(
                queryObject.getRewrittenQuery(),
                source,
                evidences.size(),
                defaultTopK,
                allowRerank,
                allowCompress
        );

        List<Evidence> result = evidences;
        if (allowDeduplicate && plan.isDeduplicate()) {
            result = evidenceDeduplicator.deduplicate(result);
        }
        if (allowRerank && plan.isRerank()) {
            result = evidenceReranker.rerank(queryObject.getRewrittenQuery(), result, plan.getTopK());
        } else if (result.size() > plan.getTopK()) {
            result = result.subList(0, plan.getTopK());
        }
        if (allowCompress && plan.isCompress()) {
            result = evidenceCompressor.compress(result, plan.getCompressionChars());
        }
        return result;
    }

    /**
     * 对跨源融合后的结果做再次去重、压缩和按来源回分配，确保最终输出更干净且满足来源配额。
     */
    private PostFusionOutcome postFusionProcess(
            Map<RetrievalSource, List<Evidence>> grouped,
            Map<RetrievalSource, Integer> topKConfig,
            List<RetrievalSource> targetSources,
            boolean allowCompress
    ) {
        List<Evidence> flattened = new ArrayList<>();
        for (RetrievalSource source : targetSources) {
            flattened.addAll(grouped.getOrDefault(source, List.of()));
        }
        int before = flattened.size();
        List<Evidence> sourceDeduplicated = evidenceFusionService.deduplicateAcrossSources(flattened);
        if (sourceDeduplicated == null) {
            sourceDeduplicated = flattened;
        }
        int afterCrossSourceDedup = sourceDeduplicated.size();
        List<Evidence> deduplicated = evidenceDeduplicator.deduplicate(sourceDeduplicated);
        int afterDedup = deduplicated.size();
        List<Evidence> compressed = allowCompress
                ? evidenceCompressor.compress(deduplicated, ragProperties.getCompressionMaxChars())
                : deduplicated;
        Map<RetrievalSource, List<Evidence>> redistributed =
                evidenceFusionService.redistributeBySource(compressed, topKConfig, targetSources);
        if (redistributed == null || redistributed.isEmpty()) {
            redistributed = redistributeBySourceLocal(compressed, topKConfig, targetSources);
        }
        Map<RetrievalSource, List<Evidence>> stableRedistributed = redistributed;
        List<RetrievalSource> hitSources = targetSources.stream()
                .filter(source -> !stableRedistributed.getOrDefault(source, List.of()).isEmpty())
                .toList();

        Map<String, Object> meta = new HashMap<>();
        meta.put("global_after_dedup", afterDedup);
        meta.put("global_dedup_removed", Math.max(0, before - afterDedup));
        meta.put("global_cross_source_dedup_removed", Math.max(0, before - afterCrossSourceDedup));
        meta.put("global_after_compression", compressed.size());
        meta.put("global_compression_removed", Math.max(0, afterDedup - compressed.size()));
        meta.put("hit_sources", hitSources.stream().map(RetrievalSource::value).toList());

        return new PostFusionOutcome(stableRedistributed, hitSources, meta);
    }

    private Map<RetrievalSource, List<Evidence>> redistributeBySourceLocal(
            List<Evidence> evidences,
            Map<RetrievalSource, Integer> topKConfig,
            List<RetrievalSource> targetSources
    ) {
        Map<RetrievalSource, List<Evidence>> grouped = new EnumMap<>(RetrievalSource.class);
        for (RetrievalSource source : targetSources) {
            grouped.put(source, new ArrayList<>());
        }
        for (Evidence evidence : evidences) {
            if (evidence == null || evidence.getSource() == null || !grouped.containsKey(evidence.getSource())) {
                continue;
            }
            RetrievalSource source = evidence.getSource();
            int limit = Math.max(1, topKConfig.getOrDefault(source, 3));
            List<Evidence> bucket = grouped.get(source);
            if (bucket.size() < limit) {
                bucket.add(evidence);
            }
        }
        return grouped;
    }

    private boolean shouldPreferMidModel(QueryObject queryObject, Map<RetrievalSource, List<Evidence>> grouped) {
        Object complexity = queryObject.getPossibleFilters() == null ? null : queryObject.getPossibleFilters().get("query_complexity");
        if ("complex".equalsIgnoreCase(String.valueOf(complexity))) {
            return true;
        }
        int total = grouped.values().stream().mapToInt(List::size).sum();
        return total > 12;
    }

    protected long resolveTimeoutMs(RetrievalRequest request) {
        RetrievalOptions options = request == null ? null : request.getOptions();
        long requestTimeout = options == null ? 0 : options.getMaxLatencyMs();
        if (requestTimeout > 0) {
            return Math.max(100, requestTimeout);
        }
        return Math.max(100, ragProperties.getDefaultTimeoutMs());
    }

    protected long remainingMs(long deadlineEpochMs) {
        return Math.max(0, deadlineEpochMs - System.currentTimeMillis());
    }

    protected RetrievalRequest withTimeout(RetrievalRequest request, long timeoutMs) {
        if (request == null) {
            return RetrievalRequest.builder()
                    .query("")
                    .options(RetrievalOptions.builder().maxLatencyMs(Math.max(100, timeoutMs)).build())
                    .build();
        }
        long bounded = Math.max(100, timeoutMs);
        return RetrievalRequest.builder()
                .query(request.getQuery())
                .sessionId(request.getSessionId())
                .conversationContext(request.getConversationContext())
                .allowedRoutes(request.getAllowedRoutes())
                .sourceScope(request.getSourceScope())
                .options(RetrievalOptions.builder()
                        .debug(request.getOptions() != null && request.getOptions().isDebug())
                        .maxLatencyMs(bounded)
                        .build())
                .build();
    }

    protected boolean isDebugEnabled(RetrievalRequest request) {
        return request != null && request.getOptions() != null && request.getOptions().isDebug();
    }

    protected Map<RetrievalSource, Integer> normalizeTopKConfig(
            Map<RetrievalSource, Integer> topKConfig,
            List<RetrievalSource> targetSources
    ) {
        Map<RetrievalSource, Integer> normalized = new EnumMap<>(RetrievalSource.class);
        for (RetrievalSource source : targetSources) {
            int topK = 3;
            if (topKConfig != null) {
                Integer configured = topKConfig.get(source);
                if (configured != null && configured > 0) {
                    topK = configured;
                }
            }
            normalized.put(source, topK);
        }
        return normalized;
    }

    protected int sumTopK(Map<RetrievalSource, Integer> topKConfig, List<RetrievalSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (RetrievalSource source : sources) {
            sum += Math.max(1, topKConfig.getOrDefault(source, 3));
        }
        return sum;
    }

    protected EvidenceFusionService getEvidenceFusionService() {
        return evidenceFusionService;
    }

    protected ModelDrivenRagPlanner getModelDrivenRagPlanner() {
        return modelDrivenRagPlanner;
    }

    protected RagProperties getRagProperties() {
        return ragProperties;
    }

    protected Map<RetrievalSource, List<Evidence>> mergeBySource(
            Map<RetrievalSource, List<Evidence>> left,
            Map<RetrievalSource, List<Evidence>> right,
            List<RetrievalSource> targetSources
    ) {
        Map<RetrievalSource, List<Evidence>> merged = new EnumMap<>(RetrievalSource.class);
        for (RetrievalSource source : targetSources) {
            List<Evidence> items = new ArrayList<>();
            items.addAll(left.getOrDefault(source, List.of()));
            items.addAll(right.getOrDefault(source, List.of()));
            merged.put(source, items);
        }
        return merged;
    }

    protected Map<RetrievalSource, List<Evidence>> initGrouped(List<RetrievalSource> targetSources) {
        Map<RetrievalSource, List<Evidence>> grouped = new EnumMap<>(RetrievalSource.class);
        for (RetrievalSource source : targetSources) {
            grouped.put(source, new ArrayList<>());
        }
        return grouped;
    }

    protected Map<RetrievalSource, List<Evidence>> ensureAllEvidenceBuckets(
            Map<RetrievalSource, List<Evidence>> grouped
    ) {
        Map<RetrievalSource, List<Evidence>> complete = new EnumMap<>(RetrievalSource.class);
        Map<RetrievalSource, List<Evidence>> safeGrouped = grouped == null ? Map.of() : grouped;
        for (RetrievalSource source : RetrievalSource.values()) {
            complete.put(source, safeGrouped.getOrDefault(source, List.of()));
        }
        return complete;
    }

    protected Map<EvidenceRole, List<Evidence>> buildEvidenceRoleGroups(
            Map<RetrievalSource, List<Evidence>> grouped
    ) {
        Map<EvidenceRole, List<Evidence>> roleGroups = new EnumMap<>(EvidenceRole.class);
        for (EvidenceRole role : EvidenceRole.values()) {
            roleGroups.put(role, new ArrayList<>());
        }
        if (grouped == null || grouped.isEmpty()) {
            return roleGroups;
        }
        for (Map.Entry<RetrievalSource, List<Evidence>> entry : grouped.entrySet()) {
            RetrievalSource source = entry.getKey();
            List<Evidence> evidences = entry.getValue();
            if (evidences == null || evidences.isEmpty()) {
                continue;
            }
            for (Evidence evidence : evidences) {
                if (evidence == null) {
                    continue;
                }
                EvidenceRole role = evidence.getRole() == null ? inferRoleFromSource(source) : evidence.getRole();
                roleGroups.get(role).add(evidence.toBuilder().role(role).build());
            }
        }
        return roleGroups;
    }

    private EvidenceRole inferRoleFromSource(RetrievalSource source) {
        if (source == null) {
            return EvidenceRole.FACT;
        }
        return switch (source) {
            case KNOWLEDGE -> EvidenceRole.FACT;
            case MEMORY -> EvidenceRole.EXPERIENCE;
            case PREFERENCE -> EvidenceRole.PREFERENCE;
        };
    }

    protected long totalEvidenceCount(Map<RetrievalSource, List<Evidence>> grouped) {
        return grouped.values().stream().mapToLong(List::size).sum();
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

    /**
     * 该结果模型用于承载来源检索结果、超时来源和过程元信息。
     */
    protected record SourceRetrieveOutcome(
            Map<RetrievalSource, List<Evidence>> grouped,
            List<RetrievalSource> timedOutSources,
            List<RetrievalSource> hitSources,
            Map<String, Object> meta
    ) {
    }

    private record PostFusionOutcome(
            Map<RetrievalSource, List<Evidence>> grouped,
            List<RetrievalSource> hitSources,
            Map<String, Object> meta
    ) {
    }
}
