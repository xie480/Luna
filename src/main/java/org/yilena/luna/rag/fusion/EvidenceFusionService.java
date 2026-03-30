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
public class EvidenceFusionService {

    private final ModelDrivenRagPlanner modelDrivenRagPlanner;

    public FusionResult fuse(
            String query,
            Map<RetrievalSource, List<Evidence>> grouped,
            Map<RetrievalSource, Integer> topKConfig,
            List<RetrievalSource> targetSources,
            boolean preferMidModel
    ) {
        List<RetrievalSource> sources = targetSources == null || targetSources.isEmpty()
                ? RetrievalSource.all()
                : targetSources;

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

        int before = all.size();
        List<Evidence> deduplicated = globalDeduplicate(all);
        int globalLimit = resolveGlobalLimit(topKConfig, sources);
        List<Evidence> ranked = modelDrivenRagPlanner.rerankGlobally(query, deduplicated, globalLimit, preferMidModel);

        Map<RetrievalSource, List<Evidence>> redistributed = redistributeBySource(ranked, topKConfig, sources);
        List<RetrievalSource> hitSources = redistributed.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();

        Map<String, Object> meta = new HashMap<>();
        meta.put("global_candidates", before);
        meta.put("global_after_dedup", deduplicated.size());
        meta.put("global_dedup_removed", Math.max(0, before - deduplicated.size()));
        meta.put("hit_sources", hitSources.stream().map(RetrievalSource::value).toList());
        return new FusionResult(redistributed, hitSources, meta);
    }

    private List<Evidence> globalDeduplicate(List<Evidence> evidences) {
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

    private Map<RetrievalSource, List<Evidence>> redistributeBySource(
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

    public record FusionResult(
            Map<RetrievalSource, List<Evidence>> grouped,
            List<RetrievalSource> hitSources,
            Map<String, Object> meta
    ) {
    }
}
