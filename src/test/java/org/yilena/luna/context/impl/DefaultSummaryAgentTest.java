package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.model.ToolState;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DefaultSummaryAgentTest {

    @Test
    void shouldFallbackToDeterministicSummaryWhenModelOutputMissing() {
        DefaultSummaryAgent agent = new DefaultSummaryAgent(
                mock(LlmClientUtil.class),
                geminiProperty(),
                new ObjectMapper()
        );
        StructuredContextPackage contextPackage = StructuredContextPackage.builder()
                .taskState(TaskRuntimeState.EXECUTING)
                .relationalState(RelationalRuntimeState.LIGHT_CHAT)
                .taskStateEntity(TaskState.builder()
                        .objective("完成任务")
                        .pendingQuestions(List.of("是否包含风险项"))
                        .finishedSteps(List.of("已完成数据收集"))
                        .failedSteps(List.of())
                        .build())
                .toolState(ToolState.builder()
                        .lastToolName("search_knowledge")
                        .lastToolStatus("SUCCESS")
                        .lastToolSemanticSummary("找到关键证据")
                        .build())
                .recentMessages(List.of(
                        Map.of("role", "USER", "content_text", "继续推进"),
                        Map.of("role", "ASSISTANT", "content_text", "我先汇总当前状态")
                ))
                .taskContext(Map.of("working_memory", Map.of("constraints_json", List.of("两天内完成"))))
                .build();

        SummaryResult result = agent.summarize(
                "继续推进",
                "我先汇总当前状态",
                contextPackage,
                List.of(),
                List.of(),
                null
        );

        assertNotNull(result);
        assertFalse(result.getNarrativeSummary().isBlank());
        assertNotNull(result.getStateSnapshot());
        assertTrue(result.getStateSnapshot().containsKey("nextStep"));
    }

    private GeminiProperty geminiProperty() {
        GeminiProperty property = new GeminiProperty();
        GeminiProperty.ModelConfig flash = new GeminiProperty.ModelConfig();
        flash.setModelName("test-flash");
        property.setFlash(flash);
        return property;
    }
}
