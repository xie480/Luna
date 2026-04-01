package org.yilena.luna.rag.retrievers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.adapters.PgRetrievalAdapter;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.EvidenceRole;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalSource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class PreferenceRetriever implements BaseRetriever {

    private final PgRetrievalAdapter pgRetrievalAdapter;

    @Override
    public RetrievalSource source() {
        return RetrievalSource.PREFERENCE;
    }

    @Override
    public List<Evidence> retrieve(QueryObject queryObject, int topK, Map<String, Object> filters) {
        String prefKey = resolvePrefKey(queryObject, filters);
        String query = effectiveQuery(queryObject);
        String vector = toVector(queryObject.getEmbedding());
        int keepTopK = Math.max(1, Math.min(3, topK));

        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.addAll(safeCall(() -> pgRetrievalAdapter.searchPreferenceByExactOrTrigram(prefKey, query, keepTopK)));
        if (candidates.size() < keepTopK && vector != null) {
            candidates.addAll(safeCall(() -> pgRetrievalAdapter.searchPreferenceByVector(vector, Math.max(keepTopK, keepTopK * 2))));
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<String, ScoredPreference> merged = new HashMap<>();
        for (Map<String, Object> row : candidates) {
            String id = str(row.get("id"));
            if (id.isBlank()) {
                continue;
            }
            ScoredPreference current = merged.get(id);
            if (current == null) {
                merged.put(id, new ScoredPreference(row, prefKey));
            } else {
                current.merge(row, prefKey);
            }
        }

        return merged.values().stream()
                .map(ScoredPreference::toEvidence)
                .sorted(Comparator.comparingDouble(Evidence::getScore).reversed())
                .limit(keepTopK)
                .toList();
    }

    private <T> List<T> safeCall(SupplierWithException<List<T>> supplier) {
        try {
            List<T> rows = supplier.get();
            return rows == null ? List.of() : rows;
        } catch (Exception ignore) {
            return List.of();
        }
    }

    private String effectiveQuery(QueryObject queryObject) {
        if (queryObject.getRewrittenQuery() != null && !queryObject.getRewrittenQuery().isBlank()) {
            return queryObject.getRewrittenQuery();
        }
        if (queryObject.getNormalizedQuery() != null && !queryObject.getNormalizedQuery().isBlank()) {
            return queryObject.getNormalizedQuery();
        }
        return Objects.toString(queryObject.getOriginalQuery(), "");
    }

    private String resolvePrefKey(QueryObject queryObject, Map<String, Object> filters) {
        if (filters != null && filters.get("pref_key") != null) {
            String fromFilter = String.valueOf(filters.get("pref_key")).trim();
            if (!fromFilter.isEmpty()) {
                return fromFilter;
            }
        }
        if (queryObject.getQueryTags() != null && queryObject.getQueryTags().contains("key_match_priority")) {
            String text = effectiveQuery(queryObject).toLowerCase();
            if (text.contains("长度")) {
                return "response_length";
            }
            if (text.contains("风格")) {
                return "response_style";
            }
            if (text.contains("语气")) {
                return "tone";
            }
        }
        return null;
    }

    private String toVector(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            return null;
        }
        return "[" + embedding.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("") + "]";
    }

    private Evidence toEvidence(Map<String, Object> row, double finalScore, double vectorScore, double keyMatchScore, double recencyScore, double textMatchScore) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("raw_id", row.get("id"));
        metadata.put("pref_key", str(row.get("pref_key")));
        metadata.put("pref_value", str(row.get("pref_value")));
        metadata.put("vector_score", vectorScore);
        metadata.put("key_match_score", keyMatchScore);
        metadata.put("recency_score", recencyScore);
        metadata.put("text_match_score", textMatchScore);
        return Evidence.builder()
                .id("preference:" + str(row.get("id")))
                .source(RetrievalSource.PREFERENCE)
                .type("preference")
                .role(EvidenceRole.PREFERENCE)
                .title(str(row.get("pref_key")))
                .content(buildContent(row))
                .score(finalScore)
                .metadata(metadata)
                .build();
    }

    private String buildContent(Map<String, Object> row) {
        return "pref_key=" + str(row.get("pref_key"))
                + ", pref_value=" + str(row.get("pref_value"))
                + ", description=" + str(row.get("description"));
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private double recencyScore(Object timeObj) {
        LocalDateTime time = null;
        if (timeObj instanceof LocalDateTime dateTime) {
            time = dateTime;
        } else if (timeObj != null) {
            try {
                time = LocalDateTime.parse(String.valueOf(timeObj).replace(" ", "T"));
            } catch (Exception ignore) {
                time = null;
            }
        }
        if (time == null) {
            return 0.3D;
        }
        long days = Math.max(0L, Duration.between(time, LocalDateTime.now()).toDays());
        return 1.0D / (1.0D + (days / 45.0D));
    }

    @FunctionalInterface
    private interface SupplierWithException<T> {
        T get() throws Exception;
    }

    private class ScoredPreference {
        private final Map<String, Object> row;
        private double vectorScore;
        private double keyMatchScore;
        private double recencyScore;
        private double textMatchScore;

        private ScoredPreference(Map<String, Object> row, String prefKey) {
            this.row = new HashMap<>(row);
            this.vectorScore = normalize(row.get("vector_score"));
            this.keyMatchScore = keyMatchScore(row, prefKey);
            this.recencyScore = recencyScore(row.get("updated_at"));
            this.textMatchScore = Math.max(normalize(row.get("text_match_score")), normalize(row.get("key_match_score")));
        }

        private void merge(Map<String, Object> other, String prefKey) {
            this.vectorScore = Math.max(this.vectorScore, normalize(other.get("vector_score")));
            this.keyMatchScore = Math.max(this.keyMatchScore, keyMatchScore(other, prefKey));
            this.recencyScore = Math.max(this.recencyScore, recencyScore(other.get("updated_at")));
            this.textMatchScore = Math.max(this.textMatchScore, Math.max(normalize(other.get("text_match_score")), normalize(other.get("key_match_score"))));
        }

        private double keyMatchScore(Map<String, Object> row, String prefKey) {
            String key = str(row.get("pref_key"));
            if (prefKey != null && prefKey.equalsIgnoreCase(key)) {
                return 1.0D;
            }
            return normalize(row.get("key_match_score"));
        }

        private Evidence toEvidence() {
            double finalScore = 0.50D * vectorScore + 0.20D * keyMatchScore + 0.20D * recencyScore + 0.10D * textMatchScore;
            return PreferenceRetriever.this.toEvidence(row, finalScore, vectorScore, keyMatchScore, recencyScore, textMatchScore);
        }

        private double normalize(Object rawScore) {
            if (rawScore instanceof Number number) {
                return Math.max(0.0D, Math.min(1.0D, number.doubleValue()));
            }
            try {
                return Math.max(0.0D, Math.min(1.0D, Double.parseDouble(str(rawScore))));
            } catch (Exception ignore) {
                return 0.0D;
            }
        }
    }
}
