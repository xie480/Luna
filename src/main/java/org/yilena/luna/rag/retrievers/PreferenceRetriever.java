package org.yilena.luna.rag.retrievers;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
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

    private final JdbcTemplate jdbcTemplate;

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
            return jdbcTemplate.queryForList(
                    "select cast(fact_id as varchar) as id, fact_key as pref_key, fact_value_text as pref_value, description " +
                            "from relational_semantic_fact where deleted = false and principal_id = cast(abs(hashtext(?)) as bigint) " +
                            "order by updated_at desc limit ?",
                    sessionId, topK
            );
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
