package org.yilena.luna.rag.retrievers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.Memory;
import org.yilena.luna.rag.adapters.PgRetrievalAdapter;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** 记忆检索器，负责会话记忆向量召回并支持 session 过滤。 */
@Component
@RequiredArgsConstructor
public class MemoryRetriever implements BaseRetriever {

    private final PgRetrievalAdapter pgRetrievalAdapter;

    @Override
    public RetrievalSource source() {
        return RetrievalSource.MEMORY;
    }

    @Override
    public List<Evidence> retrieve(QueryObject queryObject, int topK, Map<String, Object> filters) {
        String vector = queryObject.getEmbedding();
        if (vector == null || vector.isBlank() || "[]".equals(vector.trim())) {
            return Collections.emptyList();
        }
        List<Memory> records = pgRetrievalAdapter.searchMemoryByVector(vector, queryObject.getSessionId(), topK);
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        return IntStream.range(0, records.size())
                .mapToObj(index -> toEvidence(records.get(index), index, records.size()))
                .toList();
    }

    private Evidence toEvidence(Memory memory, int index, int total) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("raw_id", memory.getId());
        metadata.put("memory_type", memory.getMemoryType() != null ? memory.getMemoryType().getValue() : null);
        metadata.put("weight", memory.getWeight());
        metadata.put("session_id", memory.getSessionId());
        return Evidence.builder()
                .id("memory:" + memory.getId())
                .source(RetrievalSource.MEMORY)
                .type("memory")
                .title(null)
                .content(memory.getContent())
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
