package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DefaultGlobalContextRerankAgentTest {

    @Test
    void shouldReturnKnowledgeAndRationaleWhenModelUnavailable() {
        DefaultGlobalContextRerankAgent agent = new DefaultGlobalContextRerankAgent(
                mock(LlmClientUtil.class),
                geminiProperty(),
                new ObjectMapper()
        );
        InputReconstructionResult reconstruction = InputReconstructionResult.builder()
                .explicitTaskGoal("完成季度经营复盘")
                .normalizedUserIntent("整理季度复盘关键结论")
                .build();
        RetrievalResponse retrievalResponse = RetrievalResponse.builder()
                .route(RetrievalRoute.MODULAR)
                .rewrittenQuery("q")
                .evidences(Map.of(
                        RetrievalSource.KNOWLEDGE, List.of(
                                Evidence.builder().id("k-1").source(RetrievalSource.KNOWLEDGE).title("复盘模板").content("季度复盘包含目标、偏差与行动").score(0.91).build()
                        ),
                        RetrievalSource.MEMORY, List.of(
                                Evidence.builder().id("m-1").source(RetrievalSource.MEMORY).content("用户偏好先看结论再看过程").score(0.89).build()
                        ),
                        RetrievalSource.PREFERENCE, List.of(
                                Evidence.builder().id("p-1").source(RetrievalSource.PREFERENCE).content("输出需要简洁条目").score(0.88).build()
                        )
                ))
                .build();
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("capability_name", "search_knowledge");
        tool.put("capability_type", "TOOL");
        tool.put("description", "检索季度复盘证据");
        tool.put("requires_approval", false);
        tool.put("sensitivity", "LOW");

        ContextRerankResult result = agent.rerank(
                reconstruction,
                StructuredContextPackage.builder().sessionId("s-1").build(),
                retrievalResponse,
                List.of(tool),
                TaskRuntimeState.PLANNING
        );

        assertNotNull(result);
        assertFalse(result.getSelectedKnowledgeEvidenceBlocks().isEmpty());
        assertFalse(result.getSelectedMemoryHints().isEmpty());
        assertFalse(result.getSelectedToolCandidates().isEmpty());
        assertTrue(result.getRationaleByNode().containsKey("task_stage"));
    }

    private GeminiProperty geminiProperty() {
        GeminiProperty property = new GeminiProperty();
        GeminiProperty.ModelConfig flash = new GeminiProperty.ModelConfig();
        flash.setModelName("test-flash");
        property.setFlash(flash);
        return property;
    }
}
