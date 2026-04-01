package org.yilena.luna.rag.api;

import org.junit.jupiter.api.Test;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.EvidenceRole;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.rag.models.RoutePlan;
import org.yilena.luna.rag.pipelines.RetrievalPipeline;
import org.yilena.luna.rag.processor.QueryProcessor;
import org.yilena.luna.rag.router.RouteSelector;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalServiceImplTest {

    @Test
    void shouldMergeMetaAndPropagateRoleGroups() {
        QueryProcessor processor = mock(QueryProcessor.class);
        RouteSelector selector = mock(RouteSelector.class);
        RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
        when(pipeline.route()).thenReturn(RetrievalRoute.NATIVE);

        QueryObject queryObject = QueryObject.builder()
                .originalQuery("q")
                .normalizedQuery("q")
                .rewrittenQuery("q")
                .queryType("general_retrieval")
                .build();
        when(processor.process(any())).thenReturn(queryObject);
        when(selector.selectPlan(any(), any())).thenReturn(RoutePlan.builder()
                .route(RetrievalRoute.NATIVE)
                .sources(List.of(RetrievalSource.KNOWLEDGE))
                .topKConfig(Map.of(RetrievalSource.KNOWLEDGE, 3))
                .build());
        when(pipeline.execute(any(), any(), any())).thenReturn(RetrievalResponse.builder()
                .route(RetrievalRoute.NATIVE)
                .rewrittenQuery("q")
                .evidences(Map.of(
                        RetrievalSource.KNOWLEDGE,
                        List.of(Evidence.builder()
                                .id("knowledge:1")
                                .source(RetrievalSource.KNOWLEDGE)
                                .type("knowledge")
                                .role(EvidenceRole.FACT)
                                .content("c")
                                .score(0.8)
                                .build())
                ))
                .evidenceRoleGroups(Map.of(EvidenceRole.FACT, List.of()))
                .meta(Map.of("hit_sources", List.of("knowledge")))
                .build());

        RetrievalServiceImpl service = new RetrievalServiceImpl(processor, selector, List.of(pipeline));
        RetrievalResponse response = service.retrieve(RetrievalRequest.builder().query("q").sessionId("s1").build());

        assertEquals(RetrievalRoute.NATIVE, response.getRoute());
        assertEquals("general_retrieval", response.getMeta().get("query_type"));
        assertTrue(response.getEvidenceRoleGroups().containsKey(EvidenceRole.FACT));
        assertEquals(List.of("knowledge"), response.getMeta().get("sources_used"));
    }
}
