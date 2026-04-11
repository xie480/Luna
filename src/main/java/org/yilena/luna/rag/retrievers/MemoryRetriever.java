package org.yilena.luna.rag.retrievers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.adapters.PgRetrievalAdapter;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.EvidenceRole;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
/**
 * 该检索器负责从会话记忆中召回证据，并结合记忆类型、时效和权重信息输出标准化结果。
 */
public class MemoryRetriever implements BaseRetriever {

    /**
     * PostgreSQL 检索适配器。
     */
    private final PgRetrievalAdapter pgRetrievalAdapter;

    @Override
    public RetrievalSource source() {
        return RetrievalSource.MEMORY;
    }

    /**
     * 按会话、时间窗口和记忆类型检索候选记忆，并融合多维分数生成最终证据。
     */
    @Override
    public List<Evidence> retrieve(QueryObject queryObject, int topK, Map<String, Object> filters) {
        String sessionId = queryObject.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }

        /**
         * 先从过滤条件中提取记忆类型和时间范围，缩小会话记忆检索范围。
         */
        List<String> memoryTypes = resolveMemoryTypes(filters);
        LocalDateTime endTime = resolveEndTime(filters);
        LocalDateTime startTime = resolveStartTime(filters, endTime);
        String vector = toVector(queryObject.getEmbedding());
        String keyword = effectiveQuery(queryObject);
        TypeScoreProfile profile = resolveTypeScoreProfile(filters, queryObject.getQueryTags());

        /**
         * 根据查询形态决定先关键词还是先向量召回，兼顾精确命中与语义召回。
         */
        List<Map<String, Object>> candidates = new ArrayList<>();
        boolean exactFirst = queryObject.getQueryTags() != null && queryObject.getQueryTags().contains("exact_match_first");
        if (exactFirst) {
            candidates.addAll(safeCall(() -> pgRetrievalAdapter.searchMemoryByKeyword(
                    sessionId, keyword, memoryTypes, startTime, endTime, topK
            )));
            if (vector != null) {
                candidates.addAll(safeCall(() -> pgRetrievalAdapter.searchMemoryByVector(
                        sessionId, vector, memoryTypes, startTime, endTime, Math.max(topK, topK * 2)
                )));
            }
        } else {
            if (vector != null) {
                candidates.addAll(safeCall(() -> pgRetrievalAdapter.searchMemoryByVector(
                        sessionId, vector, memoryTypes, startTime, endTime, Math.max(topK, topK * 2)
                )));
            }
            if (candidates.size() < topK) {
                candidates.addAll(safeCall(() -> pgRetrievalAdapter.searchMemoryByKeyword(
                    sessionId, keyword, memoryTypes, startTime, endTime, topK
            )));
            }
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        /**
         * 合并不同召回通道命中的同一记忆记录，并融合各类得分后统一排序。
         */
        Map<String, ScoredMemory> merged = new HashMap<>();
        for (Map<String, Object> row : candidates) {
            String id = str(row.get("id"));
            if (id.isBlank()) {
                continue;
            }
            ScoredMemory current = merged.get(id);
            if (current == null) {
                merged.put(id, new ScoredMemory(row, profile));
            } else {
                current.merge(row);
            }
        }
        return merged.values().stream()
                .map(ScoredMemory::toEvidence)
                .sorted(Comparator.comparingDouble(Evidence::getScore).reversed())
                .limit(Math.max(1, topK))
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

    private String toVector(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            return null;
        }
        return "[" + embedding.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("") + "]";
    }

    private List<String> resolveMemoryTypes(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        Object raw = filters.get("memory_type");
        if (raw == null) {
            raw = filters.get("memory_types");
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        if (raw instanceof String text && !text.isBlank()) {
            return List.of(text.split(",")).stream().map(String::trim).filter(s -> !s.isBlank()).toList();
        }
        return List.of();
    }

    private LocalDateTime resolveStartTime(Map<String, Object> filters, LocalDateTime endTime) {
        if (filters == null) {
            return null;
        }
        Object explicit = filters.get("start_time");
        if (explicit instanceof LocalDateTime time) {
            return time;
        }
        Object window = filters.get("time_window_days");
        if (window instanceof Number number) {
            return endTime.minusDays(Math.max(1, number.longValue()));
        }
        return null;
    }

    private LocalDateTime resolveEndTime(Map<String, Object> filters) {
        if (filters == null) {
            return LocalDateTime.now();
        }
        Object explicit = filters.get("end_time");
        if (explicit instanceof LocalDateTime time) {
            return time;
        }
        return LocalDateTime.now();
    }

    private Evidence toEvidence(Map<String, Object> row, double finalScore, double vectorScore, double weightScore, double recencyScore, double typeScore) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("raw_id", row.get("id"));
        metadata.put("memory_type", str(row.get("memory_type")));
        metadata.put("session_id", str(row.get("session_id")));
        metadata.put("weight", intVal(row.get("weight"), 1));
        metadata.put("vector_score", vectorScore);
        metadata.put("weight_score", weightScore);
        metadata.put("recency_score", recencyScore);
        metadata.put("type_score", typeScore);
        return Evidence.builder()
                .id("memory:" + str(row.get("id")))
                .source(RetrievalSource.MEMORY)
                .type("memory")
                .role(resolveEvidenceRole(str(row.get("memory_type"))))
                .title(null)
                .content(str(row.get("content")))
                .score(finalScore)
                .metadata(metadata)
                .build();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int intVal(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(str(value));
        } catch (Exception ignore) {
            return fallback;
        }
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
            return 0.2D;
        }
        long days = Math.max(0L, Duration.between(time, LocalDateTime.now()).toDays());
        return 1.0D / (1.0D + (days / 30.0D));
    }

    private double typeScore(String memoryType, TypeScoreProfile profile) {
        String normalized = memoryType == null ? "" : memoryType.toUpperCase();
        return switch (profile) {
            case REFLECTIVE -> reflectiveTypeScore(normalized);
            case ADVICE -> adviceTypeScore(normalized);
            case GENERAL -> generalTypeScore(normalized);
        };
    }

    private double reflectiveTypeScore(String normalized) {
        if (normalized.contains("SUMMARY") || normalized.contains("REFLECTION") || "2".equals(normalized) || "3".equals(normalized)) {
            return 1.0D;
        }
        if (normalized.contains("FACT") || "0".equals(normalized)) {
            return 0.78D;
        }
        if (normalized.contains("DECISION")) {
            return 0.72D;
        }
        return 0.58D;
    }

    private double adviceTypeScore(String normalized) {
        if (normalized.contains("DECISION")) {
            return 1.0D;
        }
        if (normalized.contains("FACT") || normalized.contains("PREFERENCE") || "0".equals(normalized) || "1".equals(normalized)) {
            return 0.92D;
        }
        if (normalized.contains("SUCCESS") || normalized.contains("FAILURE") || normalized.contains("PARTIAL")) {
            return 0.82D;
        }
        return 0.62D;
    }

    private double generalTypeScore(String normalized) {
        if (normalized.contains("FACT") || normalized.contains("DECISION") || "0".equals(normalized)) {
            return 0.88D;
        }
        if (normalized.contains("SUMMARY") || normalized.contains("REFLECTION") || "2".equals(normalized) || "3".equals(normalized)) {
            return 0.82D;
        }
        return 0.68D;
    }

    private TypeScoreProfile resolveTypeScoreProfile(Map<String, Object> filters, List<String> queryTags) {
        String queryType = filters == null ? "" : str(filters.get("query_type"));
        if ("analysis_reasoning".equalsIgnoreCase(queryType)) {
            return TypeScoreProfile.REFLECTIVE;
        }
        if ("multi_source_reasoning".equalsIgnoreCase(queryType)) {
            return TypeScoreProfile.ADVICE;
        }
        boolean reflectiveHint = queryTags != null && queryTags.stream().anyMatch(tag ->
                "analysis_reasoning".equals(tag) || "needs_recency".equals(tag));
        if (reflectiveHint) {
            return TypeScoreProfile.REFLECTIVE;
        }
        boolean adviceHint = queryTags != null && queryTags.stream().anyMatch(tag ->
                "preference_lookup".equals(tag) || "memory_lookup".equals(tag));
        if (adviceHint) {
            return TypeScoreProfile.ADVICE;
        }
        return TypeScoreProfile.GENERAL;
    }

    private EvidenceRole resolveEvidenceRole(String memoryType) {
        String normalized = memoryType == null ? "" : memoryType.toUpperCase();
        if (normalized.contains("DECISION") || normalized.contains("SUCCESS")
                || normalized.contains("FAILURE") || normalized.contains("PARTIAL")) {
            return EvidenceRole.STRATEGY;
        }
        if (normalized.contains("PREFERENCE") || "1".equals(normalized)) {
            return EvidenceRole.PREFERENCE;
        }
        if (normalized.contains("FACT") || "0".equals(normalized)) {
            return EvidenceRole.FACT;
        }
        return EvidenceRole.EXPERIENCE;
    }

    /**
     * 可抛异常的查询供应器，用于统一包装记忆检索调用。
     */
    @FunctionalInterface
    private interface SupplierWithException<T> {
        T get() throws Exception;
    }

    /**
     * 记忆评分对象，用于融合向量、权重、时效和类型等多维得分。
     */
    private class ScoredMemory {
        private final Map<String, Object> row;
        private final TypeScoreProfile profile;
        private double vectorScore;
        private double weightScore;
        private double recencyScore;
        private double typeScore;

        private ScoredMemory(Map<String, Object> row, TypeScoreProfile profile) {
            this.row = new HashMap<>(row);
            this.profile = profile;
            this.vectorScore = Math.max(normalize(row.get("vector_score")), normalize(row.get("text_match_score")));
            this.weightScore = Math.max(0.0D, Math.min(1.0D, intVal(row.get("weight"), 1) / 10.0D));
            this.recencyScore = recencyScore(row.get("updated_at"));
            this.typeScore = typeScore(str(row.get("memory_type")), profile);
        }

        private void merge(Map<String, Object> other) {
            this.vectorScore = Math.max(this.vectorScore, Math.max(normalize(other.get("vector_score")), normalize(other.get("text_match_score"))));
            this.weightScore = Math.max(this.weightScore, Math.max(0.0D, Math.min(1.0D, intVal(other.get("weight"), 1) / 10.0D)));
            this.recencyScore = Math.max(this.recencyScore, recencyScore(other.get("updated_at")));
            this.typeScore = Math.max(this.typeScore, typeScore(str(other.get("memory_type")), profile));
        }

        private Evidence toEvidence() {
            double finalScore = 0.55D * vectorScore + 0.20D * weightScore + 0.15D * recencyScore + 0.10D * typeScore;
            Evidence evidence = MemoryRetriever.this.toEvidence(row, finalScore, vectorScore, weightScore, recencyScore, typeScore);
            Map<String, Object> metadata = new HashMap<>(evidence.getMetadata());
            metadata.put("type_score_profile", profile.value);
            return evidence.toBuilder().metadata(metadata).build();
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

    /**
     * 记忆类型评分配置枚举，用于按检索场景调整类型偏好权重。
     */
    private enum TypeScoreProfile {
        /**
         * 偏向建议类记忆的评分配置。
         */
        ADVICE("advice"),
        /**
         * 偏向反思类记忆的评分配置。
         */
        REFLECTIVE("reflective"),
        /**
         * 通用检索场景的默认评分配置。
         */
        GENERAL("general");

        private final String value;

        TypeScoreProfile(String value) {
            this.value = value;
        }
    }
}
