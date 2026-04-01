package org.yilena.luna.rag.processor;

import org.junit.jupiter.api.Test;
import org.yilena.luna.rag.adapters.EmbeddingProvider;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.models.ConversationMessage;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.planner.ModelDrivenRagPlanner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryProcessorTest {

    @Test
    void shouldBuildEmbeddingAndQueryTagsFromQuery() {
        EmbeddingProvider embeddingProvider = mock(EmbeddingProvider.class);
        ModelDrivenRagPlanner planner = mock(ModelDrivenRagPlanner.class);
        RagProperties properties = new RagProperties();
        when(embeddingProvider.embedding(anyString())).thenReturn("[0.1, 0.2, 0.3]");
        when(planner.planQuery(anyString(), anyString(), any())).thenReturn(
                ModelDrivenRagPlanner.QueryPlanDecision.builder()
                        .queryType("precise_lookup")
                        .rewrittenQuery("回答长度设置是什么")
                        .complexity("simple")
                        .build()
        );

        QueryProcessor processor = new QueryProcessor(embeddingProvider, properties, planner);
        QueryObject queryObject = processor.process(RetrievalRequest.builder()
                .query("偏好里有没有回答长度设置")
                .sessionId("s1")
                .conversationContext(List.of(ConversationMessage.builder().role("user").content("我喜欢简短回答").build()))
                .build());

        assertEquals(3, queryObject.getEmbedding().size());
        assertTrue(queryObject.getQueryTags().contains("exact_match_first"));
        assertTrue(queryObject.getQueryTags().contains("key_match_priority"));
        assertEquals("response_length", queryObject.getPossibleFilters().get("pref_key"));
    }

    @Test
    void shouldRewriteForMultiSourceReasoningWhenPlannerRewriteMissing() {
        EmbeddingProvider embeddingProvider = mock(EmbeddingProvider.class);
        ModelDrivenRagPlanner planner = mock(ModelDrivenRagPlanner.class);
        RagProperties properties = new RagProperties();
        when(embeddingProvider.embedding(anyString())).thenReturn("[0.1, 0.2]");
        when(planner.planQuery(anyString(), anyString(), any())).thenReturn(
                ModelDrivenRagPlanner.QueryPlanDecision.builder()
                        .queryType("multi_source_reasoning")
                        .rewrittenQuery(null)
                        .complexity("medium")
                        .build()
        );

        QueryProcessor processor = new QueryProcessor(embeddingProvider, properties, planner);
        QueryObject queryObject = processor.process(RetrievalRequest.builder()
                .query("结合我的记忆和偏好给建议")
                .sessionId("s1")
                .build());

        assertTrue(queryObject.getRewrittenQuery().startsWith("请执行多源联合检索并对齐证据："));
    }
}
