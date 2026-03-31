package org.yilena.luna.rag.pipelines;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.fusion.EvidenceFusionService;
import org.yilena.luna.rag.models.Evidence;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractRetrievalPipeline implements RetrievalPipeline {

    private final Map<RetrievalSource, BaseRetriever> retrieverMap;
    private final EvidenceReranker evidenceReranker;
    private final EvidenceDeduplicator evidenceDeduplicator;
    private final EvidenceCompressor evidenceCompressor;
    private final RagProperties ragProperties;
    private final ModelDrivenRagPlanner modelDrivenRagPlanner;
    private final EvidenceFusionService evidenceFusionService;

    protected SourceRetrieveOutcome retrieveBySources(
            QueryObject queryObject,
            Map<RetrievalSource, Integer> topKConfig,
            List<RetrievalSource> sources,
            boolean rerank,
            boolean compress,
            RetrievalRequest request,
            long timeoutMs
    ) {
        List<RetrievalSource> targetSources = sources == null || sources.isEmpty() ? RetrievalSource.all() : sources;
        long budgetMs = timeoutMs > 0 ? timeoutMs : resolveTimeoutMs(request);
        long deadline = System.currentTimeMillis() + budgetMs;

        Map<RetrievalSource, CompletableFuture<List<Evidence>>> retrievalFutures = new EnumMap<>(RetrievalSource.class);
        for (RetrievalSource source : targetSources) {
            int topK = Math.max(1, topKConfig.getOrDefault(source, 3));
            retrievalFutures.put(source, CompletableFuture.supplyAsync(() -> retrieveSingleSource(source, queryObject, topK)));
        }

        long remaining = remainingMs(deadline);
        if (!retrievalFutures.isEmpty() && remaining > 0) {
            CompletableFuture<?>[] all = retrievalFutures.values().toArray(CompletableFuture[]::new);
            try {
                CompletableFuture.allOf(all).get(remaining, TimeUnit.MILLISECONDS);
            } catch (Exception ignore) {
                // Partial results are allowed. We collect completed futures below.
            }
        }

        Map<RetrievalSource, List<Evidence>> processedBySource = new EnumMap<>(RetrievalSource.class);
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
            int topK = Math.max(1, topKConfig.getOrDefault(source, 3));
            sourceEvidences = processSourceEvidence(queryObject, source, sourceEvidences, topK, rerank, compress);
            processedBySource.put(source, sourceEvidences);
        }

        boolean preferMidModel = shouldPreferMidModel(queryObject, processedBySource);
        EvidenceFusionService.FusionResult fusionResult = evidenceFusionService.fuse(
                queryObject.getRewrittenQuery(),
                processedBySource,
                topKConfig,
                targetSources,
                preferMidModel
        );

        Map<String, Object> meta = new HashMap<>(fusionResult.meta());
        meta.put("timed_out_sources", timedOutSources.stream().map(RetrievalSource::value).toList());
        meta.put("timeout_ms", budgetMs);
        return new SourceRetrieveOutcome(fusionResult.grouped(), timedOutSources, fusionResult.hitSources(), meta);
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

    private List<Evidence> processSourceEvidence(
            QueryObject queryObject,
            RetrievalSource source,
            List<Evidence> evidences,
            int defaultTopK,
            boolean allowRerank,
            boolean allowCompress
    ) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }

        ModelDrivenRagPlanner.SourceProcessPlan plan = modelDrivenRagPlanner.planSourceProcessing(
                queryObject.getRewrittenQuery(),
                source,
                evidences.size(),
                defaultTopK,
                allowRerank,
                allowCompress
        );

        List<Evidence> result = evidences;
        if (plan.isDeduplicate()) {
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

    protected record SourceRetrieveOutcome(
            Map<RetrievalSource, List<Evidence>> grouped,
            List<RetrievalSource> timedOutSources,
            List<RetrievalSource> hitSources,
            Map<String, Object> meta
    ) {
    }
}
