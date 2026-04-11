package org.yilena.luna.rag.fusion;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.planner.ModelDrivenRagPlanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
/**
 * 该服务负责将多数据源检索结果做跨源融合、全局重排和按源再分配，保证最终证据既相关又均衡。
 */
public class EvidenceFusionService {

    /**
     * 基于模型的规划器，用于执行跨来源全局重排。
     */
    private final ModelDrivenRagPlanner modelDrivenRagPlanner;

    /**
     * 融合各来源证据，必要时执行全局重排，并按来源预算重新分桶。
     */
    public FusionResult fuse(
            String query,
            Map<RetrievalSource, List<Evidence>> grouped,
            Map<RetrievalSource, Integer> topKConfig,
            List<RetrievalSource> targetSources,
            boolean allowGlobalRerank,
            boolean preferMidModel
    ) {
        List<RetrievalSource> sources = targetSources == null || targetSources.isEmpty()
                ? RetrievalSource.all()
                : targetSources;

        /**
         * 先按目标来源汇总全部候选，后续统一做全局重排或直接截断。
         */
        List<Evidence> all = new ArrayList<>();
        for (RetrievalSource source : sources) {
            all.addAll(grouped.getOrDefault(source, List.of()));
        }
        if (all.isEmpty()) {
            Map<RetrievalSource, List<Evidence>> empty = new EnumMap<>(RetrievalSource.class);
            for (RetrievalSource source : sources) {
                empty.put(source, List.of());
            }
            return new FusionResult(empty, List.of(), Map.of("global_candidates", 0));
        }

        /**
         * 根据总预算决定全局保留上限，再按配置决定是否启用跨源重排。
         */
        int globalLimit = resolveGlobalLimit(topKConfig, sources);
        List<Evidence> ranked = allowGlobalRerank
                ? modelDrivenRagPlanner.rerankGlobally(query, all, globalLimit, preferMidModel)
                : all.stream().limit(globalLimit).toList();

        /**
         * 将全局排序结果重新按来源分配，确保最终输出仍满足各来源的 topK 约束。
         */
        Map<RetrievalSource, List<Evidence>> redistributed = redistributeBySource(ranked, topKConfig, sources);
        List<RetrievalSource> hitSources = redistributed.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();

        Map<String, Object> meta = new HashMap<>();
        meta.put("global_candidates", all.size());
        meta.put("global_after_fusion", ranked.size());
        meta.put("global_rerank_enabled", allowGlobalRerank);
        meta.put("hit_sources", hitSources.stream().map(RetrievalSource::value).toList());
        return new FusionResult(redistributed, hitSources, meta);
    }

    /**
     * 对跨来源证据做内容去重，优先保留得分更高的一条并在元数据中记录融合来源。
     */
    public List<Evidence> deduplicateAcrossSources(List<Evidence> evidences) {
        Map<String, Evidence> byKey = new HashMap<>();
        Map<String, Set<String>> fusedSources = new HashMap<>();
        for (Evidence evidence : evidences) {
            String key = normalize(evidence.getContent());
            Evidence existing = byKey.get(key);
            if (existing == null || evidence.getScore() > existing.getScore()) {
                byKey.put(key, evidence);
            }
            fusedSources.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(evidence.getSource().value());
        }
        List<Evidence> result = new ArrayList<>();
        for (Map.Entry<String, Evidence> entry : byKey.entrySet()) {
            Evidence evidence = entry.getValue();
            Set<String> mergedSources = fusedSources.getOrDefault(entry.getKey(), Set.of(evidence.getSource().value()));
            Map<String, Object> metadata = new HashMap<>(evidence.getMetadata());
            metadata.put("fused_sources", mergedSources);
            result.add(evidence.toBuilder().metadata(metadata).build());
        }
        result.sort(Comparator.comparingDouble(Evidence::getScore).reversed());
        return result;
    }

    /**
     * 按来源预算把全局排序后的证据重新分桶，避免某个来源独占全部结果。
     */
    public Map<RetrievalSource, List<Evidence>> redistributeBySource(
            List<Evidence> ranked,
            Map<RetrievalSource, Integer> topKConfig,
            List<RetrievalSource> sources
    ) {
        Map<RetrievalSource, List<Evidence>> result = new EnumMap<>(RetrievalSource.class);
        for (RetrievalSource source : sources) {
            result.put(source, new ArrayList<>());
        }
        for (Evidence evidence : ranked) {
            RetrievalSource source = evidence.getSource();
            if (!result.containsKey(source)) {
                continue;
            }
            int limit = Math.max(1, topKConfig.getOrDefault(source, 3));
            List<Evidence> bucket = result.get(source);
            if (bucket.size() < limit) {
                bucket.add(evidence);
            }
            if (isAllBucketsFull(result, topKConfig)) {
                break;
            }
        }
        for (RetrievalSource source : sources) {
            result.put(source, Collections.unmodifiableList(result.get(source)));
        }
        return Collections.unmodifiableMap(result);
    }

    private boolean isAllBucketsFull(Map<RetrievalSource, List<Evidence>> result, Map<RetrievalSource, Integer> topKConfig) {
        for (Map.Entry<RetrievalSource, List<Evidence>> entry : result.entrySet()) {
            int limit = Math.max(1, topKConfig.getOrDefault(entry.getKey(), 3));
            if (entry.getValue().size() < limit) {
                return false;
            }
        }
        return true;
    }

    private int resolveGlobalLimit(Map<RetrievalSource, Integer> topKConfig, List<RetrievalSource> sources) {
        int total = 0;
        for (RetrievalSource source : sources) {
            total += Math.max(1, topKConfig.getOrDefault(source, 3));
        }
        return Math.max(1, total);
    }

    private String normalize(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim().toLowerCase();
        if (normalized.length() <= 320) {
            return normalized;
        }
        return normalized.substring(0, 320);
    }

    /**
     * 该记录用于承载融合后的证据分组、命中来源和过程元信息。
     */
    public record FusionResult(
            Map<RetrievalSource, List<Evidence>> grouped,
            List<RetrievalSource> hitSources,
            Map<String, Object> meta
    ) {
    }
}
