package org.yilena.luna.rag.retrievers;

import org.junit.jupiter.api.Test;
import org.yilena.luna.rag.adapters.PgRetrievalAdapter;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.QueryObject;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PreferenceRetrieverTest {

    @Test
    void shouldKeepTopThreeCorePreferences() {
        PgRetrievalAdapter adapter = mock(PgRetrievalAdapter.class);
        PreferenceRetriever retriever = new PreferenceRetriever(adapter);

        List<Map<String, Object>> rows = List.of(
                row("1", "response_length", "short", 1.0, LocalDateTime.now()),
                row("2", "response_style", "direct", 0.7, LocalDateTime.now().minusDays(1)),
                row("3", "tone", "neutral", 0.6, LocalDateTime.now().minusDays(3)),
                row("4", "emoji", "none", 0.5, LocalDateTime.now().minusDays(10))
        );
        when(adapter.searchPreferenceByExactOrTrigram(eq("response_length"), anyString(), anyInt())).thenReturn(rows);
        when(adapter.searchPreferenceByVector(anyString(), anyInt())).thenReturn(List.of());

        QueryObject queryObject = QueryObject.builder()
                .originalQuery("偏好里有没有回答长度设置")
                .normalizedQuery("偏好里有没有回答长度设置")
                .rewrittenQuery("偏好里有没有回答长度设置")
                .embedding(List.of(0.2, 0.3))
                .queryTags(List.of("key_match_priority"))
                .possibleFilters(Map.of("pref_key", "response_length"))
                .build();

        List<Evidence> result = retriever.retrieve(queryObject, 10, queryObject.getPossibleFilters());

        assertEquals(3, result.size());
        assertEquals("preference:1", result.get(0).getId());
    }

    private Map<String, Object> row(String id, String key, String value, double score, LocalDateTime time) {
        return Map.of(
                "id", id,
                "pref_key", key,
                "pref_value", value,
                "description", key + "=" + value,
                "key_match_score", score,
                "text_match_score", score,
                "updated_at", time
        );
    }

    @Test
    void shouldResolveToneByNormalizedKeyword() {
        PgRetrievalAdapter adapter = mock(PgRetrievalAdapter.class);
        PreferenceRetriever retriever = new PreferenceRetriever(adapter);

        when(adapter.searchPreferenceByExactOrTrigram(eq("tone"), anyString(), anyInt())).thenReturn(List.of(
                row("1", "tone", "neutral", 1.0, LocalDateTime.now())
        ));
        when(adapter.searchPreferenceByVector(anyString(), anyInt())).thenReturn(List.of());

        QueryObject queryObject = QueryObject.builder()
                .originalQuery("按我的口吻回答")
                .normalizedQuery("按我的口吻回答")
                .rewrittenQuery("按我的口吻回答")
                .embedding(List.of(0.2, 0.3))
                .queryTags(List.of("key_match_priority"))
                .build();

        List<Evidence> result = retriever.retrieve(queryObject, 3, Map.of());
        assertEquals(1, result.size());
        assertTrue("tone".equals(result.get(0).getMetadata().get("pref_key")));
    }
}
