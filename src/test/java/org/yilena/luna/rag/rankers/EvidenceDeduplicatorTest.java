package org.yilena.luna.rag.rankers;

import org.junit.jupiter.api.Test;
import org.yilena.luna.rag.adapters.EmbeddingProvider;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.EvidenceRole;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.support.SemanticTextService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvidenceDeduplicatorTest {

    @Test
    void shouldMergePreferenceConflictsByPrefKey() {
        EmbeddingProvider embeddingProvider = mock(EmbeddingProvider.class);
        when(embeddingProvider.embedding(anyString())).thenReturn("[0.1,0.2]");
        RagProperties properties = new RagProperties();
        EvidenceDeduplicator deduplicator = new EvidenceDeduplicator(properties, new SemanticTextService(embeddingProvider));
        Evidence low = Evidence.builder()
                .id("preference:1")
                .source(RetrievalSource.PREFERENCE)
                .type("preference")
                .role(EvidenceRole.PREFERENCE)
                .content("pref_key=response_style, pref_value=简洁, description=风格")
                .score(0.6)
                .metadata(Map.of("pref_key", "response_style", "pref_value", "简洁"))
                .build();
        Evidence high = Evidence.builder()
                .id("preference:2")
                .source(RetrievalSource.PREFERENCE)
                .type("preference")
                .role(EvidenceRole.PREFERENCE)
                .content("pref_key=response_style, pref_value=自然, description=风格")
                .score(0.9)
                .metadata(Map.of("pref_key", "response_style", "pref_value", "自然"))
                .build();
        Evidence memory = Evidence.builder()
                .id("memory:1")
                .source(RetrievalSource.MEMORY)
                .type("memory")
                .role(EvidenceRole.EXPERIENCE)
                .content("x")
                .score(0.5)
                .build();

        List<Evidence> result = deduplicator.deduplicate(List.of(low, high, memory));

        assertEquals(2, result.size());
        Evidence merged = result.stream()
                .filter(e -> e.getSource() == RetrievalSource.PREFERENCE)
                .findFirst()
                .orElseThrow();
        assertEquals("preference:2", merged.getId());
        boolean conflictMerged = Boolean.TRUE.equals(merged.getMetadata().get("preference_conflict"));
        boolean semanticMerged = Boolean.TRUE.equals(merged.getMetadata().get("semantic_deduplicated"));
        assertTrue(conflictMerged || semanticMerged);
    }
}
