package org.yilena.luna.rag.pipelines;

import org.junit.jupiter.api.Test;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.fusion.EvidenceFusionService;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.EvidenceRole;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalRoute;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchPipelineTest {

    @Test
    void shouldReturnSearchRouteAndRoleGroups() {
        BaseRetriever knowledgeRetriever = mock(BaseRetriever.class);
        when(knowledgeRetriever.source()).thenReturn(RetrievalSource.KNOWLEDGE);
        when(knowledgeRetriever.retrieve(any(), anyInt(), anyMap())).thenReturn(List.of(
                Evidence.builder()
                        .id("knowledge:1")
                        .source(RetrievalSource.KNOWLEDGE)
                        .type("knowledge")
                        .role(EvidenceRole.FACT)
                        .content("k")
                        .score(0.9)
                        .build()
        ));

        EvidenceReranker reranker = mock(EvidenceReranker.class);
        when(reranker.rerank(anyString(), any(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        EvidenceDeduplicator deduplicator = new EvidenceDeduplicator();
        RagProperties properties = new RagProperties();
        EvidenceCompressor compressor = new EvidenceCompressor(properties);
        ModelDrivenRagPlanner planner = mock(ModelDrivenRagPlanner.class);
        when(planner.planSourceProcessing(anyString(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(ModelDrivenRagPlanner.SourceProcessPlan.builder()
                        .deduplicate(true).rerank(false).compress(false).topK(3).compressionChars(500).build());
        EvidenceFusionService fusionService = mock(EvidenceFusionService.class);

        SearchPipeline pipeline = new SearchPipeline(
                List.of(knowledgeRetriever), reranker, deduplicator, compressor, properties, planner, fusionService
        );

        RetrievalResponse response = pipeline.execute(
                QueryObject.builder()
                        .originalQuery("有没有记录")
                        .normalizedQuery("有没有记录")
                        .rewrittenQuery("有没有记录")
                        .build(),
                RoutePlan.builder()
                        .route(RetrievalRoute.SEARCH)
                        .sources(List.of(RetrievalSource.KNOWLEDGE))
                        .topKConfig(Map.of(RetrievalSource.KNOWLEDGE, 3))
                        .build(),
                RetrievalRequest.builder().query("有没有记录").build()
        );

        assertEquals(RetrievalRoute.SEARCH, response.getRoute());
        assertEquals(1, response.getEvidences().get(RetrievalSource.KNOWLEDGE).size());
        assertTrue(response.getEvidenceRoleGroups().get(EvidenceRole.FACT).size() >= 1);
    }
}
