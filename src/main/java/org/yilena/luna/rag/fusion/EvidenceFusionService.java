package org.yilena.luna.rag.fusion; // define package

import lombok.RequiredArgsConstructor; // import dependency
import org.springframework.stereotype.Component; // import dependency
import org.yilena.luna.rag.models.Evidence; // import dependency
import org.yilena.luna.rag.models.RetrievalSource; // import dependency
import org.yilena.luna.rag.planner.ModelDrivenRagPlanner; // import dependency

import java.util.ArrayList; // import dependency
import java.util.Collections; // import dependency
import java.util.Comparator; // import dependency
import java.util.EnumMap; // import dependency
import java.util.HashMap; // import dependency
import java.util.LinkedHashSet; // import dependency
import java.util.List; // import dependency
import java.util.Map; // import dependency
import java.util.Set; // import dependency

@Component // declare annotation
@RequiredArgsConstructor // declare annotation
public class EvidenceFusionService { // define class

    private final ModelDrivenRagPlanner modelDrivenRagPlanner; // business logic

    public FusionResult fuse( // business logic
            String query, // business logic
            Map<RetrievalSource, List<Evidence>> grouped, // business logic
            Map<RetrievalSource, Integer> topKConfig, // business logic
            List<RetrievalSource> targetSources, // business logic
            boolean preferMidModel // business logic
    ) { // block start
        List<RetrievalSource> sources = targetSources == null || targetSources.isEmpty() // assignment or init
                ? RetrievalSource.all() // business logic
                : targetSources; // business logic

        List<Evidence> all = new ArrayList<>(); // assignment or init
        for (RetrievalSource source : sources) { // loop logic
            all.addAll(grouped.getOrDefault(source, List.of())); // business logic
        } // block end
        if (all.isEmpty()) { // branch logic
            Map<RetrievalSource, List<Evidence>> empty = new EnumMap<>(RetrievalSource.class); // define class
            for (RetrievalSource source : sources) { // loop logic
                empty.put(source, List.of()); // business logic
            } // block end
            return new FusionResult(empty, List.of(), Map.of("global_candidates", 0)); // return result
        } // block end

        int before = all.size(); // assignment or init
        List<Evidence> deduplicated = globalDeduplicate(all); // assignment or init
        int globalLimit = resolveGlobalLimit(topKConfig, sources); // assignment or init
        List<Evidence> ranked = modelDrivenRagPlanner.rerankGlobally(query, deduplicated, globalLimit, preferMidModel); // assignment or init

        Map<RetrievalSource, List<Evidence>> redistributed = redistributeBySource(ranked, topKConfig, sources); // assignment or init
        List<RetrievalSource> hitSources = redistributed.entrySet().stream() // assignment or init
                .filter(entry -> !entry.getValue().isEmpty()) // business logic
                .map(Map.Entry::getKey) // business logic
                .toList(); // business logic

        Map<String, Object> meta = new HashMap<>(); // assignment or init
        meta.put("global_candidates", before); // business logic
        meta.put("global_after_dedup", deduplicated.size()); // business logic
        meta.put("global_dedup_removed", Math.max(0, before - deduplicated.size())); // business logic
        meta.put("hit_sources", hitSources.stream().map(RetrievalSource::value).toList()); // business logic
        return new FusionResult(redistributed, hitSources, meta); // return result
    } // block end

    private List<Evidence> globalDeduplicate(List<Evidence> evidences) { // method definition
        Map<String, Evidence> byKey = new HashMap<>(); // assignment or init
        Map<String, Set<String>> fusedSources = new HashMap<>(); // assignment or init
        for (Evidence evidence : evidences) { // loop logic
            String key = normalize(evidence.getContent()); // assignment or init
            Evidence existing = byKey.get(key); // assignment or init
            if (existing == null || evidence.getScore() > existing.getScore()) { // branch logic
                byKey.put(key, evidence); // business logic
            } // block end
            fusedSources.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(evidence.getSource().value()); // business logic
        } // block end
        List<Evidence> result = new ArrayList<>(); // assignment or init
        for (Map.Entry<String, Evidence> entry : byKey.entrySet()) { // loop logic
            Evidence evidence = entry.getValue(); // assignment or init
            Set<String> mergedSources = fusedSources.getOrDefault(entry.getKey(), Set.of(evidence.getSource().value())); // assignment or init
            Map<String, Object> metadata = new HashMap<>(evidence.getMetadata()); // assignment or init
            metadata.put("fused_sources", mergedSources); // business logic
            result.add(evidence.toBuilder().metadata(metadata).build()); // business logic
        } // block end
        result.sort(Comparator.comparingDouble(Evidence::getScore).reversed()); // business logic
        return result; // return result
    } // block end

    private Map<RetrievalSource, List<Evidence>> redistributeBySource( // business logic
            List<Evidence> ranked, // business logic
            Map<RetrievalSource, Integer> topKConfig, // business logic
            List<RetrievalSource> sources // business logic
    ) { // block start
        Map<RetrievalSource, List<Evidence>> result = new EnumMap<>(RetrievalSource.class); // define class
        for (RetrievalSource source : sources) { // loop logic
            result.put(source, new ArrayList<>()); // business logic
        } // block end
        for (Evidence evidence : ranked) { // loop logic
            RetrievalSource source = evidence.getSource(); // assignment or init
            if (!result.containsKey(source)) { // branch logic
                continue; // enum or const item
            } // block end
            int limit = Math.max(1, topKConfig.getOrDefault(source, 3)); // assignment or init
            List<Evidence> bucket = result.get(source); // assignment or init
            if (bucket.size() < limit) { // branch logic
                bucket.add(evidence); // business logic
            } // block end
            if (isAllBucketsFull(result, topKConfig)) { // branch logic
                break; // enum or const item
            } // block end
        } // block end
        for (RetrievalSource source : sources) { // loop logic
            result.put(source, Collections.unmodifiableList(result.get(source))); // business logic
        } // block end
        return Collections.unmodifiableMap(result); // return result
    } // block end

    private boolean isAllBucketsFull(Map<RetrievalSource, List<Evidence>> result, Map<RetrievalSource, Integer> topKConfig) { // method definition
        for (Map.Entry<RetrievalSource, List<Evidence>> entry : result.entrySet()) { // loop logic
            int limit = Math.max(1, topKConfig.getOrDefault(entry.getKey(), 3)); // assignment or init
            if (entry.getValue().size() < limit) { // branch logic
                return false; // return result
            } // block end
        } // block end
        return true; // return result
    } // block end

    private int resolveGlobalLimit(Map<RetrievalSource, Integer> topKConfig, List<RetrievalSource> sources) { // method definition
        int total = 0; // assignment or init
        for (RetrievalSource source : sources) { // loop logic
            total += Math.max(1, topKConfig.getOrDefault(source, 3)); // assignment or init
        } // block end
        return Math.max(1, total); // return result
    } // block end

    private String normalize(String content) { // method definition
        if (content == null) { // branch logic
            return ""; // return result
        } // block end
        String normalized = content.replaceAll("\\s+", " ").trim().toLowerCase(); // assignment or init
        if (normalized.length() <= 320) { // branch logic
            return normalized; // return result
        } // block end
        return normalized.substring(0, 320); // return result
    } // block end

    public record FusionResult( // define record
            Map<RetrievalSource, List<Evidence>> grouped, // business logic
            List<RetrievalSource> hitSources, // business logic
            Map<String, Object> meta // business logic
    ) { // block start
    } // block end
} // block end
