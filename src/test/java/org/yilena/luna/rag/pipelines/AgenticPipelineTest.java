package org.yilena.luna.rag.pipelines;

import org.junit.jupiter.api.Test;
import org.yilena.luna.rag.adapters.EmbeddingProvider;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.fusion.EvidenceFusionService;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.models.RoutePlan;
import org.yilena.luna.rag.planner.ModelDrivenRagPlanner;
import org.yilena.luna.rag.rankers.EvidenceCompressor;
import org.yilena.luna.rag.rankers.EvidenceDeduplicator;
import org.yilena.luna.rag.rankers.EvidenceReranker;
import org.yilena.luna.rag.retrievers.BaseRetriever;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgenticPipelineTest {

    @Test
    void shouldFallbackToModularWhenEvidenceInsufficient() {
        BaseRetriever knowledgeRetriever = mock(BaseRetriever.class);
        when(knowledgeRetriever.source()).thenReturn(RetrievalSource.KNOWLEDGE);
        when(knowledgeRetriever.retrieve(any(), anyInt(), anyMap())).thenReturn(List.of());

        EvidenceReranker reranker = mock(EvidenceReranker.class);
        EvidenceDeduplicator deduplicator = new EvidenceDeduplicator();
        RagProperties properties = new RagProperties();
        properties.setAgenticMinEvidence(2);
        properties.setAgenticMaxCalls(2);
        properties.setAgenticMaxSteps(1);
        EvidenceCompressor compressor = new EvidenceCompressor(properties);

        ModelDrivenRagPlanner planner = mock(ModelDrivenRagPlanner.class);
        when(planner.planAgentStages(anyString(), anyList(), anyInt())).thenReturn(List.of(
                ModelDrivenRagPlanner.AgentStage.builder()
                        .objective("first")
                        .rewrittenQuery("q")
                        .sources(List.of(RetrievalSource.KNOWLEDGE))
                        .build()
        ));

        EvidenceFusionService fusionService = mock(EvidenceFusionService.class);
        when(fusionService.fuse(anyString(), anyMap(), anyMap(), anyList(), anyBoolean())).thenReturn(
                new EvidenceFusionService.FusionResult(
                        Map.of(RetrievalSource.KNOWLEDGE, List.<Evidence>of()),
                        List.of(),
                        Map.of("hit_sources", List.of())
                )
        );

        EmbeddingProvider embeddingProvider = mock(EmbeddingProvider.class);
        when(embeddingProvider.embedding(anyString())).thenReturn("[0.1,0.2]");

        AgenticPipeline pipeline = new AgenticPipeline(
                List.of(knowledgeRetriever),
                reranker,
                deduplicator,
                compressor,
                properties,
                planner,
                fusionService,
                embeddingProvider
        );

        RetrievalResponse response = pipeline.execute(
                QueryObject.builder()
                        .originalQuery("分析一下")
                        .normalizedQuery("分析一下")
                        .rewrittenQuery("分析一下")
                        .embedding(List.of(0.1, 0.2))
                        .queryType("analysis_reasoning")
                        .build(),
                RoutePlan.builder()
                        .route(org.yilena.luna.rag.models.RetrievalRoute.AGENTIC)
                        .sources(List.of(RetrievalSource.KNOWLEDGE))
                        .needsRewrite(true)
                        .needsRerank(true)
                        .queryType("analysis_reasoning")
                        .topKConfig(Map.of(RetrievalSource.KNOWLEDGE, 3))
                        .build(),
                RetrievalRequest.builder()
                        .query("分析一下")
                        .sessionId("s1")
                        .sourceScope(List.of(RetrievalSource.KNOWLEDGE))
                        .build()
        );

        assertEquals(org.yilena.luna.rag.models.RetrievalRoute.MODULAR, response.getRoute());
        assertTrue(Boolean.TRUE.equals(response.getMeta().get("fallback_modular")));
    }
}
