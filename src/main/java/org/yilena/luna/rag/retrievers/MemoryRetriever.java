package org.yilena.luna.rag.retrievers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.mapper.RagMemoryMapper;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Component
@RequiredArgsConstructor
public class MemoryRetriever implements BaseRetriever {

    private final RagMemoryMapper ragMemoryMapper;

    @Override
    public RetrievalSource source() {
        return RetrievalSource.MEMORY;
    }

    @Override
    public List<Evidence> retrieve(QueryObject queryObject, int topK, Map<String, Object> filters) {
        String sessionId = queryObject.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> rows = queryMemoryRows(sessionId, topK <= 0 ? 10 : topK);
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        return IntStream.range(0, rows.size())
                .mapToObj(index -> toEvidence(rows.get(index), index, rows.size()))
                .toList();
    }

    private List<Map<String, Object>> queryMemoryRows(String sessionId, int topK) {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.addAll(ragMemoryMapper.selectTaskFactMemory(sessionId, topK));
            rows.addAll(ragMemoryMapper.selectTaskEpisodeMemory(sessionId, Math.max(1, topK / 2)));
            rows.addAll(ragMemoryMapper.selectRelationalEpisodeMemory(sessionId, Math.max(1, topK / 2)));
            return rows.stream().limit(topK).toList();
        } catch (Exception ignore) {
            return Collections.emptyList();
        }
    }

    private Evidence toEvidence(Map<String, Object> row, int index, int total) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("raw_id", row.get("id"));
        metadata.put("memory_type", str(row.get("memory_type")));
        metadata.put("session_id", str(row.get("ref")));
        return Evidence.builder()
                .id("memory:" + str(row.get("id")))
                .source(RetrievalSource.MEMORY)
                .type("memory")
                .title(null)
                .content(str(row.get("content")))
                .score(rankScore(index, total))
                .metadata(metadata)
                .build();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private double rankScore(int index, int total) {
        if (total <= 1) {
            return 1.0D;
        }
        return 1.0D - ((double) index / (double) total);
    }
}
