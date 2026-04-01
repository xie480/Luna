package org.yilena.luna.rag.rankers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.support.SemanticTextService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Evidence deduplicator supporting exact and semantic deduplication. */
@Component
@RequiredArgsConstructor
public class EvidenceDeduplicator {

    private final RagProperties ragProperties;
    private final SemanticTextService semanticTextService;

    public List<Evidence> deduplicate(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<Evidence> exactDeduped = new ArrayList<>();
        for (Evidence evidence : evidences) {
            String key = evidence.getSource().value() + "::" + normalize(evidence.getContent());
            if (seen.add(key)) {
                exactDeduped.add(evidence);
            }
        }
        List<Evidence> semanticDeduped = deduplicateBySemanticSimilarity(exactDeduped);
        return deduplicatePreferenceConflicts(semanticDeduped);
    }

    private List<Evidence> deduplicateBySemanticSimilarity(List<Evidence> evidences) {
        if (evidences.size() <= 1) {
            return evidences;
        }

        List<Evidence> ranked = evidences.stream()
                .sorted(Comparator.comparingDouble(Evidence::getScore).reversed())
                .toList();

        List<Evidence> kept = new ArrayList<>();
        Map<String, List<Double>> embeddingCache = new HashMap<>();

        for (Evidence candidate : ranked) {
            int duplicateOf = -1;
            double bestSimilarity = 0.0;
            for (int i = 0; i < kept.size(); i++) {
                Evidence winner = kept.get(i);
                double similarity = semanticTextService.similarity(
                        winner.getContent(),
                        candidate.getContent(),
                        embeddingCache
                );
                double threshold = winner.getSource() == candidate.getSource()
                        ? ragProperties.getDedupSemanticSimilarityThreshold()
                        : ragProperties.getDedupSemanticCrossSourceThreshold();
                if (similarity >= threshold) {
                    duplicateOf = i;
                    bestSimilarity = similarity;
                    break;
                }
            }

            if (duplicateOf < 0) {
                kept.add(candidate);
                continue;
            }

            Evidence winner = kept.get(duplicateOf);
            Map<String, Object> metadata = winner.getMetadata() == null
                    ? new HashMap<>()
                    : new HashMap<>(winner.getMetadata());
            metadata.put("semantic_deduplicated", true);
            metadata.put("semantic_dedup_similarity", bestSimilarity);
            metadata.put("semantic_dedup_count", ((Number) metadata.getOrDefault("semantic_dedup_count", 1)).intValue() + 1);
            List<String> mergedIds = toStringList(metadata.get("semantic_dedup_merged_ids"));
            mergedIds.add(candidate.getId());
            metadata.put("semantic_dedup_merged_ids", mergedIds);

            Set<String> mergedSources = new LinkedHashSet<>(toStringList(metadata.get("semantic_dedup_merged_sources")));
            mergedSources.add(winner.getSource().value());
            mergedSources.add(candidate.getSource().value());
            metadata.put("semantic_dedup_merged_sources", new ArrayList<>(mergedSources));

            kept.set(duplicateOf, winner.toBuilder().metadata(Collections.unmodifiableMap(metadata)).build());
        }
        return kept;
    }

    private List<String> toStringList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
            return values;
        }
        return new ArrayList<>();
    }

    private List<Evidence> deduplicatePreferenceConflicts(List<Evidence> evidences) {
        Map<String, List<Evidence>> preferenceByKey = new HashMap<>();
        List<Evidence> others = new ArrayList<>();

        for (Evidence evidence : evidences) {
            if (evidence.getSource() != RetrievalSource.PREFERENCE) {
                others.add(evidence);
                continue;
            }
            String prefKey = resolvePrefKey(evidence);
            if (prefKey.isBlank()) {
                others.add(evidence);
                continue;
            }
            preferenceByKey.computeIfAbsent(prefKey, ignored -> new ArrayList<>()).add(evidence);
        }

        List<Evidence> mergedPreferences = new ArrayList<>();
        for (Map.Entry<String, List<Evidence>> entry : preferenceByKey.entrySet()) {
            List<Evidence> conflicts = entry.getValue();
            if (conflicts.size() == 1) {
                mergedPreferences.add(conflicts.get(0));
                continue;
            }
            mergedPreferences.add(mergePreferenceConflict(entry.getKey(), conflicts));
        }

        List<Evidence> result = new ArrayList<>(others.size() + mergedPreferences.size());
        result.addAll(others);
        result.addAll(mergedPreferences);
        return result;
    }

    private Evidence mergePreferenceConflict(String prefKey, List<Evidence> conflicts) {
        List<Evidence> sorted = conflicts.stream()
                .sorted(Comparator.comparingDouble(Evidence::getScore).reversed())
                .toList();
        Evidence winner = sorted.get(0);
        List<String> conflictValues = sorted.stream()
                .map(this::resolvePrefValue)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();

        Map<String, Object> metadata = new HashMap<>(winner.getMetadata());
        metadata.put("pref_key", prefKey);
        metadata.put("preference_conflict", true);
        metadata.put("preference_conflict_count", conflicts.size());
        metadata.put("preference_conflict_values", conflictValues);
        metadata.put("preference_conflict_resolved_by", "highest_score");
        metadata.put("preference_conflict_sources", conflicts.stream()
                .map(Evidence::getId)
                .collect(Collectors.toCollection(ArrayList::new)));

        String mergedContent = mergePreferenceContent(prefKey, winner, conflictValues);
        return winner.toBuilder()
                .content(mergedContent)
                .metadata(Collections.unmodifiableMap(metadata))
                .build();
    }

    private String mergePreferenceContent(String prefKey, Evidence winner, List<String> conflictValues) {
        String winnerValue = resolvePrefValue(winner);
        if (conflictValues.size() <= 1 || winnerValue.isBlank()) {
            return winner.getContent();
        }
        return "pref_key=" + prefKey
                + ", resolved_value=" + winnerValue
                + ", alternatives=" + String.join(" | ", conflictValues);
    }

    private String resolvePrefKey(Evidence evidence) {
        if (evidence.getMetadata() == null) {
            return "";
        }
        Object prefKey = evidence.getMetadata().get("pref_key");
        return prefKey == null ? "" : String.valueOf(prefKey).trim().toLowerCase();
    }

    private String resolvePrefValue(Evidence evidence) {
        if (evidence.getMetadata() != null && evidence.getMetadata().get("pref_value") != null) {
            return String.valueOf(evidence.getMetadata().get("pref_value")).trim();
        }
        String content = evidence.getContent() == null ? "" : evidence.getContent();
        int idx = content.indexOf("pref_value=");
        if (idx < 0) {
            return "";
        }
        String tail = content.substring(idx + "pref_value=".length()).trim();
        int end = tail.indexOf(',');
        return end >= 0 ? tail.substring(0, end).trim() : tail;
    }

    private String normalize(String content) {
        if (content == null) {
            return "";
        }
        String cleaned = content.replaceAll("\\s+", " ").trim().toLowerCase();
        if (cleaned.length() > 240) {
            return cleaned.substring(0, 240);
        }
        return cleaned;
    }
}
