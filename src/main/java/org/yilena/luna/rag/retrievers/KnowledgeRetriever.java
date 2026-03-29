package org.yilena.luna.rag.retrievers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.KnowledgeBase;
import org.yilena.luna.rag.adapters.PgRetrievalAdapter;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** 知识库检索器，负责知识库向量召回并转换为标准 Evidence。 */
@Component
@RequiredArgsConstructor
public class KnowledgeRetriever implements BaseRetriever {

    private final PgRetrievalAdapter pgRetrievalAdapter;

    @Override
    public RetrievalSource source() {
        return RetrievalSource.KNOWLEDGE;
    }

    @Override
    public List<Evidence> retrieve(QueryObject queryObject, int topK, Map<String, Object> filters) {
        String vector = queryObject.getEmbedding();
        if (vector == null || vector.isBlank() || "[]".equals(vector.trim())) {
            return Collections.emptyList();
        }
        List<KnowledgeBase> records = pgRetrievalAdapter.searchKnowledgeByVector(vector, topK);
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        return IntStream.range(0, records.size())
                .mapToObj(index -> toEvidence(records.get(index), index, records.size()))
                .toList();
    }

    private Evidence toEvidence(KnowledgeBase kb, int index, int total) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("raw_id", kb.getId());
        metadata.put("source_type", kb.getSourceType() != null ? kb.getSourceType().getValue() : null);
        metadata.put("source_path", kb.getSourcePath());
        return Evidence.builder()
                .id("knowledge:" + kb.getId())
                .source(RetrievalSource.KNOWLEDGE)
                .type("knowledge")
                .title(kb.getTitle())
                .content(kb.getContent())
                .score(rankScore(index, total))
                .metadata(metadata)
                .build();
    }

    private double rankScore(int index, int total) {
        if (total <= 1) {
            return 1.0D;
        }
        return 1.0D - ((double) index / (double) total);
    }
}
