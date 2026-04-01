package org.yilena.luna.rag.rankers;

import org.springframework.stereotype.Component;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 证据去重器，按来源与归一化内容消除重复证据。 */
@Component
public class EvidenceDeduplicator {

    public List<Evidence> deduplicate(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<Evidence> sourceDeduped = new ArrayList<>();
        for (Evidence evidence : evidences) {
            String key = evidence.getSource().value() + "::" + normalize(evidence.getContent());
            if (seen.add(key)) {
                sourceDeduped.add(evidence);
            }
        }
        return deduplicatePreferenceConflicts(sourceDeduped);
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
