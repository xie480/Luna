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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryRetrieverTest {

    @Test
    void shouldUseReflectiveProfileForAnalysisReasoning() {
        PgRetrievalAdapter adapter = mock(PgRetrievalAdapter.class);
        MemoryRetriever retriever = new MemoryRetriever(adapter);

        List<Map<String, Object>> rows = List.of(
                row("1", "3", "summary", 0.8),
                row("2", "0", "fact", 0.8)
        );
        when(adapter.searchMemoryByVector(anyString(), anyString(), anyList(), any(), any(), anyInt())).thenReturn(rows);
        when(adapter.searchMemoryByKeyword(anyString(), anyString(), anyList(), any(), any(), anyInt())).thenReturn(List.of());

        QueryObject queryObject = QueryObject.builder()
                .originalQuery("分析我的变化")
                .normalizedQuery("分析我的变化")
                .rewrittenQuery("分析我的变化")
                .sessionId("s1")
                .embedding(List.of(0.1, 0.2))
                .queryTags(List.of("analysis_reasoning"))
                .build();

        List<Evidence> result = retriever.retrieve(queryObject, 2, Map.of("query_type", "analysis_reasoning"));
        assertEquals(2, result.size());
        assertTrue(result.get(0).getMetadata().get("type_score_profile").toString().equals("reflective"));
    }

    private Map<String, Object> row(String id, String memoryType, String content, double vectorScore) {
        return Map.of(
                "id", id,
                "session_id", "s1",
                "memory_type", memoryType,
                "content", content,
                "weight", 5,
                "updated_at", LocalDateTime.now(),
                "vector_score", vectorScore
        );
    }
}
