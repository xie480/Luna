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
import org.yilena.luna.rag.support.SemanticTextService;

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

class ModularPipelineTest {

    @Test
    void shouldGroupEvidenceByRoleAndApplyMultiSourceFallbackRewrite() {
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

        BaseRetriever memoryRetriever = mock(BaseRetriever.class);
        when(memoryRetriever.source()).thenReturn(RetrievalSource.MEMORY);
        when(memoryRetriever.retrieve(any(), anyInt(), anyMap())).thenReturn(List.of(
                Evidence.builder()
                        .id("memory:1")
                        .source(RetrievalSource.MEMORY)
                        .type("memory")
                        .role(EvidenceRole.STRATEGY)
                        .content("m")
                        .score(0.8)
                        .build()
        ));

        EvidenceReranker reranker = mock(EvidenceReranker.class);
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        RagProperties properties = new RagProperties();
        var embeddingProvider = mock(org.yilena.luna.rag.adapters.EmbeddingProvider.class);
        when(embeddingProvider.embedding(anyString())).thenReturn("[0.1,0.2]");
        SemanticTextService semanticTextService = new SemanticTextService(embeddingProvider);
        EvidenceDeduplicator deduplicator = new EvidenceDeduplicator(properties, semanticTextService);
        EvidenceCompressor compressor = new EvidenceCompressor(properties, semanticTextService);

        ModelDrivenRagPlanner planner = mock(ModelDrivenRagPlanner.class);
        when(planner.planSourceProcessing(anyString(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(ModelDrivenRagPlanner.SourceProcessPlan.builder()
                        .deduplicate(false)
                        .rerank(false)
                        .compress(false)
                        .topK(5)
                        .compressionChars(500)
                        .build());

        EvidenceFusionService fusionService = mock(EvidenceFusionService.class);
        when(fusionService.fuse(anyString(), anyMap(), anyMap(), anyList(), anyBoolean(), anyBoolean())).thenReturn(
                new EvidenceFusionService.FusionResult(
                        Map.of(
                                RetrievalSource.KNOWLEDGE, List.of(
                                        Evidence.builder()
                                                .id("knowledge:1")
                                                .source(RetrievalSource.KNOWLEDGE)
                                                .type("knowledge")
                                                .role(EvidenceRole.FACT)
                                                .content("k")
                                                .score(0.9)
                                                .build()
                                ),
                                RetrievalSource.MEMORY, List.of(
                                        Evidence.builder()
                                                .id("memory:1")
                                                .source(RetrievalSource.MEMORY)
                                                .type("memory")
                                                .role(EvidenceRole.STRATEGY)
                                                .content("m")
                                                .score(0.8)
                                                .build()
                                )
                        ),
                        List.of(RetrievalSource.KNOWLEDGE, RetrievalSource.MEMORY),
                        Map.of("hit_sources", List.of("knowledge", "memory"))
                )
        );
        when(fusionService.deduplicateAcrossSources(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fusionService.redistributeBySource(anyList(), anyMap(), anyList())).thenReturn(
                Map.of(
                        RetrievalSource.KNOWLEDGE, List.of(
                                Evidence.builder()
                                        .id("knowledge:1")
                                        .source(RetrievalSource.KNOWLEDGE)
                                        .type("knowledge")
                                        .role(EvidenceRole.FACT)
                                        .content("k")
                                        .score(0.9)
                                        .build()
                        ),
                        RetrievalSource.MEMORY, List.of(
                                Evidence.builder()
                                        .id("memory:1")
                                        .source(RetrievalSource.MEMORY)
                                        .type("memory")
                                        .role(EvidenceRole.STRATEGY)
                                        .content("m")
                                        .score(0.8)
                                        .build()
                        )
                )
        );

        ModularPipeline pipeline = new ModularPipeline(
                List.of(knowledgeRetriever, memoryRetriever),
                reranker,
                deduplicator,
                compressor,
                properties,
                planner,
                fusionService
        );

        RetrievalResponse response = pipeline.execute(
                QueryObject.builder()
                        .originalQuery("结合我的记忆和偏好给建议")
                        .normalizedQuery("结合我的记忆和偏好给建议")
                        .rewrittenQuery("结合我的记忆和偏好给建议")
                        .queryType("multi_source_reasoning")
                        .possibleFilters(Map.of("inferred_sources", List.of("knowledge", "memory")))
                        .build(),
                RoutePlan.builder()
                        .route(RetrievalRoute.MODULAR)
                        .sources(List.of(RetrievalSource.KNOWLEDGE, RetrievalSource.MEMORY))
                        .needsRewrite(true)
                        .queryType("multi_source_reasoning")
                        .topKConfig(Map.of(RetrievalSource.KNOWLEDGE, 2, RetrievalSource.MEMORY, 2))
                        .build(),
                RetrievalRequest.builder()
                        .query("结合我的记忆和偏好给建议")
                        .sourceScope(List.of(RetrievalSource.KNOWLEDGE, RetrievalSource.MEMORY))
                        .build()
        );

        assertTrue(response.getRewrittenQuery().startsWith("请执行多源联合检索并对齐证据："));
        assertEquals(1, response.getEvidenceRoleGroups().get(EvidenceRole.FACT).size());
        assertEquals(1, response.getEvidenceRoleGroups().get(EvidenceRole.STRATEGY).size());
        assertTrue(Boolean.TRUE.equals(response.getMeta().get("evidence_role_grouping")));
    }
}
