package org.yilena.luna.rag.api;

import org.junit.jupiter.api.Test;
import org.yilena.luna.rag.adapters.EmbeddingProvider;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.fusion.EvidenceFusionService;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.EvidenceRole;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.pipelines.SearchPipeline;
import org.yilena.luna.rag.planner.ModelDrivenRagPlanner;
import org.yilena.luna.rag.processor.QueryProcessor;
import org.yilena.luna.rag.rankers.EvidenceCompressor;
import org.yilena.luna.rag.rankers.EvidenceDeduplicator;
import org.yilena.luna.rag.rankers.EvidenceReranker;
import org.yilena.luna.rag.retrievers.BaseRetriever;
import org.yilena.luna.rag.support.SemanticTextService;
import org.yilena.luna.rag.router.RouteSelector;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalServiceE2eRegressionTest {

    @Test
    void shouldCompleteSearchPathEndToEnd() {
        RagProperties properties = new RagProperties();
        properties.sanitizeConfiguredKeywords();

        ModelDrivenRagPlanner planner = mock(ModelDrivenRagPlanner.class);
        when(planner.planQuery(anyString(), anyString(), any())).thenReturn(
                ModelDrivenRagPlanner.QueryPlanDecision.builder()
                        .queryType("precise_lookup")
                        .rewrittenQuery("有没有拖延相关知识")
                        .complexity("simple")
                        .build()
        );
        when(planner.planSourceProcessing(anyString(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(ModelDrivenRagPlanner.SourceProcessPlan.builder()
                        .deduplicate(true).rerank(false).compress(false).topK(3).compressionChars(500).build());

        EmbeddingProvider embeddingProvider = mock(EmbeddingProvider.class);
        when(embeddingProvider.embedding(anyString())).thenReturn("[0.1,0.2]");

        QueryProcessor processor = new QueryProcessor(embeddingProvider, properties, planner);
        RouteSelector selector = new RouteSelector(properties);

        BaseRetriever retriever = new BaseRetriever() {
            @Override
            public RetrievalSource source() {
                return RetrievalSource.KNOWLEDGE;
            }

            @Override
            public List<Evidence> retrieve(QueryObject queryObject, int topK, Map<String, Object> filters) {
                return List.of(Evidence.builder()
                        .id("knowledge:1")
                        .source(RetrievalSource.KNOWLEDGE)
                        .type("knowledge")
                        .role(EvidenceRole.FACT)
                        .content("拖延知识")
                        .score(0.9)
                        .build());
            }
        };

        EvidenceReranker reranker = mock(EvidenceReranker.class);
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        EvidenceFusionService fusionService = mock(EvidenceFusionService.class);
        SearchPipeline searchPipeline = new SearchPipeline(
                List.of(retriever),
                reranker,
                new EvidenceDeduplicator(properties, new SemanticTextService(embeddingProvider)),
                new EvidenceCompressor(properties, new SemanticTextService(embeddingProvider)),
                properties,
                planner,
                fusionService
        );

        RetrievalServiceImpl service = new RetrievalServiceImpl(processor, selector, List.of(searchPipeline));
        var response = service.retrieve(RetrievalRequest.builder().query("有没有拖延相关知识").sessionId("s1").build());

        assertEquals(RetrievalRoute.SEARCH, response.getRoute());
        assertEquals(1, response.getEvidences().get(RetrievalSource.KNOWLEDGE).size());
        assertTrue(response.getMeta().containsKey("latency_ms"));
    }
}
