package org.yilena.luna.rag.fusion;

import org.junit.jupiter.api.Test;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.EvidenceRole;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.planner.ModelDrivenRagPlanner;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvidenceFusionServiceTest {

    @Test
    void shouldRerankAndRedistributeBySource() {
        ModelDrivenRagPlanner planner = mock(ModelDrivenRagPlanner.class);
        when(planner.rerankGlobally(anyString(), any(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        EvidenceFusionService service = new EvidenceFusionService(planner);
        Evidence k = Evidence.builder()
                .id("knowledge:1")
                .source(RetrievalSource.KNOWLEDGE)
                .type("knowledge")
                .role(EvidenceRole.FACT)
                .content("same content")
                .score(0.7)
                .build();
        Evidence m = Evidence.builder()
                .id("memory:1")
                .source(RetrievalSource.MEMORY)
                .type("memory")
                .role(EvidenceRole.EXPERIENCE)
                .content("same content")
                .score(0.9)
                .build();

        var result = service.fuse(
                "q",
                Map.of(RetrievalSource.KNOWLEDGE, List.of(k), RetrievalSource.MEMORY, List.of(m)),
                Map.of(RetrievalSource.KNOWLEDGE, 1, RetrievalSource.MEMORY, 1),
                List.of(RetrievalSource.KNOWLEDGE, RetrievalSource.MEMORY),
                true,
                false
        );

        assertEquals(2, result.meta().get("global_candidates"));
        assertTrue(Boolean.TRUE.equals(result.meta().get("global_rerank_enabled")));
        assertTrue(result.grouped().get(RetrievalSource.MEMORY).size() == 1);
    }
}
