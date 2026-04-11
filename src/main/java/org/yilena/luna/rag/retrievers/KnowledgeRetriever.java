package org.yilena.luna.rag.retrievers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.KnowledgeChunkRecord;
import org.yilena.luna.enums.SourceType;
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
import java.util.Set;

/**
 * 该检索器负责从知识库中召回候选分片，并融合向量、全文、时效和来源权重生成标准化证据。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeRetriever implements BaseRetriever {

    /**
     * PostgreSQL 检索适配器。
     */
    private final PgRetrievalAdapter pgRetrievalAdapter;

    @Override
    public RetrievalSource source() {
        return RetrievalSource.KNOWLEDGE;
    }

    /**
     * 按查询特征组合精确检索、全文检索和向量检索结果，并汇总为最终知识证据。
     */
    @Override
    public List<Evidence> retrieve(QueryObject queryObject, int topK, Map<String, Object> filters) {
        String query = effectiveQuery(queryObject);
        String vector = toVector(queryObject.getEmbedding());
        boolean exactFirst = isExactFirst(queryObject, filters);
        List<Integer> sourceTypes = parseSourceTypes(filters);
        List<KnowledgeChunkRecord> candidates = new ArrayList<>();

        /**
         * 先根据查询标签决定检索顺序，精确查找场景优先命中原文一致或高相似文本。
         */
        if (exactFirst) {
            candidates.addAll(safeCall(() -> pgRetrievalAdapter.searchKnowledgeByExact(query, topK, sourceTypes)));
            candidates.addAll(safeCall(() -> pgRetrievalAdapter.searchKnowledgeByFts(query, topK, sourceTypes)));
            candidates.addAll(safeCall(() -> pgRetrievalAdapter.searchKnowledgeByTrigram(query, topK, sourceTypes)));
            candidates.addAll(safeCall(() -> pgRetrievalAdapter.searchKnowledgeByKeyword(query, topK, sourceTypes)));
            if (candidates.size() < topK && vector != null) {
                candidates.addAll(safeCall(() -> pgRetrievalAdapter.searchKnowledgeByVector(vector, Math.max(topK, topK * 2), sourceTypes)));
            }
        } else {
            if (vector != null) {
                candidates.addAll(safeCall(() -> pgRetrievalAdapter.searchKnowledgeByVector(vector, Math.max(topK, topK * 2), sourceTypes)));
            }
            candidates.addAll(safeCall(() -> pgRetrievalAdapter.searchKnowledgeByFts(query, topK, sourceTypes)));
            if (candidates.size() < topK) {
                candidates.addAll(safeCall(() -> pgRetrievalAdapter.searchKnowledgeByTrigram(query, topK, sourceTypes)));
                candidates.addAll(safeCall(() -> pgRetrievalAdapter.searchKnowledgeByKeyword(query, topK, sourceTypes)));
            }
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        /**
         * 将多种检索通道返回的同一知识块按 ID 合并，避免重复命中在后续排序中重复计权。
         */
        Map<String, ScoredKnowledge> merged = new HashMap<>();
        for (KnowledgeChunkRecord item : candidates) {
            if (item == null) {
                continue;
            }
            String id = String.valueOf(item.getChunkId() != null ? item.getChunkId() : item.getId());
            ScoredKnowledge current = merged.get(id);
            if (current == null) {
                merged.put(id, new ScoredKnowledge(item));
            } else {
                current.merge(item);
            }
        }

        /**
         * 最后把融合后的分数写回 Evidence，并按最终分排序后截断到目标数量。
         */
        return merged.values().stream()
                .map(ScoredKnowledge::toEvidence)
                .sorted(Comparator.comparingDouble(Evidence::getScore).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    private Evidence toEvidence(KnowledgeChunkRecord kb, double finalScore, double vectorScore, double ftsScore, double recencyScore, double sourcePrior) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("raw_id", kb.getChunkId() != null ? kb.getChunkId() : kb.getId());
        metadata.put("doc_id", kb.getDocId());
        metadata.put("chunk_id", kb.getChunkId());
        metadata.put("source_type", kb.getSourceType() != null ? kb.getSourceType().getValue() : null);
        metadata.put("source_path", kb.getSourcePath());
        metadata.put("vector_score", vectorScore);
        metadata.put("fts_score", ftsScore);
        metadata.put("recency_score", recencyScore);
        metadata.put("source_prior", sourcePrior);
        return Evidence.builder()
                .id("knowledge:" + (kb.getChunkId() != null ? kb.getChunkId() : kb.getId()))
                .source(RetrievalSource.KNOWLEDGE)
                .type("knowledge")
                .role(EvidenceRole.FACT)
                .title(kb.getTitle())
                .content(kb.getContent())
                .score(finalScore)
                .metadata(metadata)
                .build();
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

    private boolean isExactFirst(QueryObject queryObject, Map<String, Object> filters) {
        if (queryObject.getQueryTags() != null && queryObject.getQueryTags().contains("exact_match_first")) {
            return true;
        }
        Object mode = filters == null ? null : filters.get("search_mode");
        return "exact_first".equalsIgnoreCase(String.valueOf(mode));
    }

    private String toVector(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            return null;
        }
        return "[" + embedding.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("") + "]";
    }

    private List<Integer> parseSourceTypes(Map<String, Object> filters) {
        if (filters == null) {
            return List.of();
        }
        Object raw = filters.get("knowledge_source_types");
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> values) {
            return values.stream()
                    .map(String::valueOf)
                    .map(this::toInteger)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        }
        if (raw instanceof String text) {
            return Set.of(text.split(",")).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(this::toInteger)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        }
        return List.of();
    }

    private Integer toInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignore) {
            return null;
        }
    }

    private double recencyScore(LocalDateTime updatedAt) {
        if (updatedAt == null) {
            return 0.2D;
        }
        long days = Math.max(0L, Duration.between(updatedAt, LocalDateTime.now()).toDays());
        return 1.0D / (1.0D + (days / 30.0D));
    }

    private double sourcePrior(SourceType sourceType) {
        if (sourceType == null) {
            return 0.6D;
        }
        return switch (sourceType) {
            case MANUAL_INPUT -> 1.0D;
            case FILE -> 0.8D;
            case WEB_SEARCH -> 0.6D;
        };
    }

    private List<KnowledgeChunkRecord> safeCall(SupplierWithException<List<KnowledgeChunkRecord>> supplier) {
        try {
            List<KnowledgeChunkRecord> items = supplier.get();
            return items == null ? List.of() : items;
        } catch (Exception ignore) {
            return List.of();
        }
    }

    /**
     * 可抛异常的查询供应器，用于统一包装知识检索调用。
     */
    @FunctionalInterface
    private interface SupplierWithException<T> {
        T get() throws Exception;
    }

    /**
     * 知识评分对象，用于聚合多路得分并生成最终证据分值。
     */
    private class ScoredKnowledge {
        private final KnowledgeChunkRecord record;
        private double vectorScore;
        private double ftsScore;
        private double recencyScore;
        private double sourcePrior;

        private ScoredKnowledge(KnowledgeChunkRecord record) {
            this.record = record;
            this.vectorScore = normalize(record.getVectorScore());
            this.ftsScore = normalize(record.getFtsScore());
            this.recencyScore = recencyScore(record.getUpdatedAt());
            this.sourcePrior = sourcePrior(record.getSourceType());
        }

        private void merge(KnowledgeChunkRecord other) {
            this.vectorScore = Math.max(this.vectorScore, normalize(other.getVectorScore()));
            this.ftsScore = Math.max(this.ftsScore, normalize(other.getFtsScore()));
            this.recencyScore = Math.max(this.recencyScore, recencyScore(other.getUpdatedAt()));
            this.sourcePrior = Math.max(this.sourcePrior, sourcePrior(other.getSourceType()));
        }

        private Evidence toEvidence() {
            double finalScore = 0.60D * vectorScore + 0.25D * ftsScore + 0.10D * recencyScore + 0.05D * sourcePrior;
            return KnowledgeRetriever.this.toEvidence(record, finalScore, vectorScore, ftsScore, recencyScore, sourcePrior);
        }

        private double normalize(Double score) {
            if (score == null || score.isNaN() || score.isInfinite()) {
                return 0.0D;
            }
            return Math.max(0.0D, Math.min(1.0D, score));
        }
    }
}
