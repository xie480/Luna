package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.model.ToolState;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DefaultInputReconstructionAgentTest {

    @Test
    void shouldUseStateAndFullShortTermMemoryForReconstruction() {
        DefaultInputReconstructionAgent agent = new DefaultInputReconstructionAgent(
                mock(LlmClientUtil.class),
                new GeminiProperty(),
                new ObjectMapper()
        );
        List<Map<String, Object>> shortTermMemory = IntStream.range(0, 25)
                .mapToObj(i -> Map.<String, Object>of(
                        "role", i % 2 == 0 ? "USER" : "ASSISTANT",
                        "content_text", "msg-" + i
                ))
                .toList();
        StructuredContextPackage contextPackage = StructuredContextPackage.builder()
                .taskState(TaskRuntimeState.PLANNING)
                .relationalState(RelationalRuntimeState.LIGHT_CHAT)
                .taskStateEntity(TaskState.builder()
                        .taskId("p-1")
                        .sessionId("s-1")
                        .objective("complete Q2 operation review")
                        .currentStage("PLANNING")
                        .currentNode("node-2")
                        .confirmedSlots(Map.of())
                        .pendingQuestions(List.of("whether include budget variance"))
                        .finishedSteps(List.of())
                        .failedSteps(List.of())
                        .retryCount(0)
                        .nextActionHint("continue")
                        .build())
                .toolState(ToolState.builder()
                        .lastToolName("search_knowledge")
                        .lastToolInput("")
                        .lastToolStatus("SUCCESS")
                        .lastToolRawResultRef("ref-1")
                        .lastToolSemanticSummary("budget data found")
                        .toolCallHistoryRefs(List.of("ref-1"))
                        .build())
                .recentMessages(shortTermMemory)
                .taskContext(Map.of("working_memory", Map.of("goal_refined", "complete Q2 operation review")))
                .build();

        InputReconstructionResult result = agent.reconstruct(
                "s-1",
                "give me an executable version before tomorrow",
                contextPackage,
                TaskRuntimeState.PLANNING,
                RelationalRuntimeState.LIGHT_CHAT
        );

        assertEquals("complete Q2 operation review", result.getExplicitTaskGoal());
        assertEquals("tomorrow", result.getTimeScope());
        assertTrue(result.getNormalizedUserIntent().contains("recentDialog="));
        assertTrue(result.getNormalizedUserIntent().contains("msg-0"));
        assertTrue(result.getNormalizedUserIntent().contains("msg-24"));
        assertTrue(result.getReformulatedQueryForRag().contains("goal=complete Q2 operation review"));
    }
}
