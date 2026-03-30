package org.yilena.luna.rag.retrievers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.mapper.RagMemoryMapper;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Component
@RequiredArgsConstructor
public class PreferenceRetriever implements BaseRetriever {

    private final RagMemoryMapper ragMemoryMapper;

    @Override
    public RetrievalSource source() {
        return RetrievalSource.PREFERENCE;
    }

    @Override
    public List<Evidence> retrieve(QueryObject queryObject, int topK, Map<String, Object> filters) {
        String sessionId = queryObject.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> rows = queryPreferenceRows(sessionId, topK <= 0 ? 10 : topK);
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        return IntStream.range(0, rows.size())
                .mapToObj(index -> toEvidence(rows.get(index), index, rows.size()))
                .toList();
    }

    private List<Map<String, Object>> queryPreferenceRows(String sessionId, int topK) {
        try {
            return ragMemoryMapper.selectPreferenceMemory(sessionId, topK);
        } catch (Exception ignore) {
            return Collections.emptyList();
        }
    }

    private Evidence toEvidence(Map<String, Object> row, int index, int total) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("raw_id", row.get("id"));
        metadata.put("pref_key", str(row.get("pref_key")));
        metadata.put("pref_value", str(row.get("pref_value")));
        return Evidence.builder()
                .id("preference:" + str(row.get("id")))
                .source(RetrievalSource.PREFERENCE)
                .type("preference")
                .title(str(row.get("pref_key")))
                .content(buildContent(row))
                .score(rankScore(index, total))
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

    private double rankScore(int index, int total) {
        if (total <= 1) {
            return 1.0D;
        }
        return 1.0D - ((double) index / (double) total);
    }
}
