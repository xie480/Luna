package org.yilena.luna.rag.retrievers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.UserPreference;
import org.yilena.luna.rag.adapters.PgRetrievalAdapter;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** 偏好检索器，负责用户偏好向量召回并构建可消费证据。 */
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
        String vector = queryObject.getEmbedding();
        if (vector == null || vector.isBlank() || "[]".equals(vector.trim())) {
            return Collections.emptyList();
        }
        List<UserPreference> records = pgRetrievalAdapter.searchPreferenceByVector(vector, topK);
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        return IntStream.range(0, records.size())
                .mapToObj(index -> toEvidence(records.get(index), index, records.size()))
                .toList();
    }

    private Evidence toEvidence(UserPreference preference, int index, int total) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("raw_id", preference.getId());
        metadata.put("pref_key", preference.getPrefKey());
        metadata.put("pref_value", preference.getPrefValue());
        return Evidence.builder()
                .id("preference:" + preference.getId())
                .source(RetrievalSource.PREFERENCE)
                .type("preference")
                .title(preference.getPrefKey())
                .content(buildContent(preference))
                .score(rankScore(index, total))
                .metadata(metadata)
                .build();
    }

    private String buildContent(UserPreference preference) {
        return "pref_key=" + nullSafe(preference.getPrefKey())
                + ", pref_value=" + nullSafe(preference.getPrefValue())
                + ", description=" + nullSafe(preference.getDescription());
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private double rankScore(int index, int total) {
        if (total <= 1) {
            return 1.0D;
        }
        return 1.0D - ((double) index / (double) total);
    }
}
