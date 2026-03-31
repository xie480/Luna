package org.yilena.luna.rag.retrievers;

import org.junit.jupiter.api.Test;
import org.yilena.luna.entity.KnowledgeChunkRecord;
import org.yilena.luna.enums.SourceType;
import org.yilena.luna.rag.adapters.PgRetrievalAdapter;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.QueryObject;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeRetrieverTest {

    @Test
    void shouldUseExactFirstAndHybridScore() {
        PgRetrievalAdapter adapter = mock(PgRetrievalAdapter.class);
        KnowledgeRetriever retriever = new KnowledgeRetriever(adapter);

        KnowledgeChunkRecord exact = KnowledgeChunkRecord.builder()
                .id(1L)
                .chunkId(1L)
                .title("A")
                .content("alpha")
                .sourceType(SourceType.MANUAL_INPUT)
                .updatedAt(LocalDateTime.now())
                .ftsScore(1.0)
                .build();
        KnowledgeChunkRecord vector = KnowledgeChunkRecord.builder()
                .id(1L)
                .chunkId(1L)
                .title("A")
                .content("alpha")
                .sourceType(SourceType.MANUAL_INPUT)
                .updatedAt(LocalDateTime.now())
                .vectorScore(0.9)
                .build();
        KnowledgeChunkRecord second = KnowledgeChunkRecord.builder()
                .id(2L)
                .chunkId(2L)
                .title("B")
                .content("beta")
                .sourceType(SourceType.WEB_SEARCH)
                .updatedAt(LocalDateTime.now().minusDays(30))
                .vectorScore(0.6)
                .ftsScore(0.5)
                .build();

        when(adapter.searchKnowledgeByExact(anyString(), anyInt())).thenReturn(List.of(exact));
        when(adapter.searchKnowledgeByFts(anyString(), anyInt())).thenReturn(List.of(exact, second));
        when(adapter.searchKnowledgeByKeyword(anyString(), anyInt())).thenReturn(List.of());
        when(adapter.searchKnowledgeByVector(anyString(), anyInt())).thenReturn(List.of(vector, second));

        QueryObject queryObject = QueryObject.builder()
                .originalQuery("上次那条知识")
                .normalizedQuery("上次那条知识")
                .rewrittenQuery("上次那条知识")
                .embedding(List.of(0.1, 0.2))
                .queryTags(List.of("exact_match_first"))
                .build();

        List<Evidence> result = retriever.retrieve(queryObject, 2, Map.of("search_mode", "exact_first"));

        assertEquals(2, result.size());
        Set<String> ids = result.stream().map(Evidence::getId).collect(java.util.stream.Collectors.toSet());
        assertTrue(ids.contains("knowledge_chunk:1"));
        verify(adapter, atLeastOnce()).searchKnowledgeByExact(anyString(), anyInt());
        verify(adapter, atLeastOnce()).searchKnowledgeByFts(anyString(), anyInt());
    }
}
