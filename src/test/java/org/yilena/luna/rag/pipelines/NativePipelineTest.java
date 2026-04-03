package org.yilena.luna.rag.pipelines;

import org.junit.jupiter.api.Test;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.fusion.EvidenceFusionService;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.EvidenceRole;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.models.RoutePlan;
import org.yilena.luna.rag.planner.ModelDrivenRagPlanner;
import org.yilena.luna.rag.rankers.EvidenceCompressor;
import org.yilena.luna.rag.rankers.EvidenceDeduplicator;
import org.yilena.luna.rag.rankers.EvidenceReranker;
import org.yilena.luna.rag.retrievers.BaseRetriever;
import org.yilena.luna.rag.support.SemanticTextService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NativePipelineTest {

    @Test
    void shouldSelectPrimaryFromInferredSources() {
        BaseRetriever memoryRetriever = mock(BaseRetriever.class);
        when(memoryRetriever.source()).thenReturn(RetrievalSource.MEMORY);
        when(memoryRetriever.retrieve(any(), anyInt(), anyMap())).thenReturn(List.of(
                Evidence.builder()
                        .id("memory:1")
                        .source(RetrievalSource.MEMORY)
                        .type("memory")
                        .role(EvidenceRole.EXPERIENCE)
                        .content("m")
                        .score(0.8)
                        .build()
        ));
        BaseRetriever knowledgeRetriever = mock(BaseRetriever.class);
        when(knowledgeRetriever.source()).thenReturn(RetrievalSource.KNOWLEDGE);
        when(knowledgeRetriever.retrieve(any(), anyInt(), anyMap())).thenReturn(List.of());

        EvidenceReranker reranker = mock(EvidenceReranker.class);
        when(reranker.rerank(anyString(), any(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        RagProperties properties = new RagProperties();
        var embeddingProvider = mock(org.yilena.luna.rag.adapters.EmbeddingProvider.class);
        when(embeddingProvider.embedding(anyString())).thenReturn("[0.1,0.2]");
        SemanticTextService semanticTextService = new SemanticTextService(embeddingProvider);
        EvidenceDeduplicator deduplicator = new EvidenceDeduplicator(properties, semanticTextService);
        EvidenceCompressor compressor = new EvidenceCompressor(properties, semanticTextService);
        ModelDrivenRagPlanner planner = mock(ModelDrivenRagPlanner.class);
        when(planner.planSourceProcessing(anyString(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(ModelDrivenRagPlanner.SourceProcessPlan.builder()
                        .deduplicate(true).rerank(false).compress(false).topK(3).compressionChars(500).build());
        EvidenceFusionService fusionService = mock(EvidenceFusionService.class);

        NativePipeline pipeline = new NativePipeline(
                List.of(memoryRetriever, knowledgeRetriever), reranker, deduplicator, compressor, properties, planner, fusionService
        );

        var response = pipeline.execute(
                QueryObject.builder()
                        .originalQuery("我的历史记录")
                        .normalizedQuery("我的历史记录")
                        .rewrittenQuery("我的历史记录")
                        .possibleFilters(Map.of("inferred_sources", List.of("memory")))
                        .build(),
                RoutePlan.builder()
                        .sources(List.of(RetrievalSource.MEMORY, RetrievalSource.KNOWLEDGE))
                        .topKConfig(Map.of(RetrievalSource.MEMORY, 3, RetrievalSource.KNOWLEDGE, 3))
                        .build(),
                RetrievalRequest.builder()
                        .query("我的历史记录")
                        .sourceScope(List.of(RetrievalSource.MEMORY, RetrievalSource.KNOWLEDGE))
                        .build()
        );

        assertEquals(1, response.getEvidences().get(RetrievalSource.MEMORY).size());
        verify(memoryRetriever).retrieve(any(), anyInt(), anyMap());
        verify(knowledgeRetriever, never()).retrieve(any(), anyInt(), anyMap());
    }
}
