package org.yilena.luna.context.impl;

import org.junit.jupiter.api.Test;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.model.ToolState;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultInputReconstructionAgentTest {

    @Test
    void shouldUseStateAndRecentMemoryForReconstruction() {
        DefaultInputReconstructionAgent agent = new DefaultInputReconstructionAgent();
        StructuredContextPackage contextPackage = StructuredContextPackage.builder()
                .taskState(TaskRuntimeState.PLANNING)
                .relationalState(RelationalRuntimeState.LIGHT_CHAT)
                .taskStateEntity(TaskState.builder()
                        .taskId("p-1")
                        .sessionId("s-1")
                        .objective("完成Q2经营复盘")
                        .currentStage("PLANNING")
                        .currentNode("node-2")
                        .confirmedSlots(Map.of())
                        .pendingQuestions(List.of("是否包含预算偏差"))
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
                        .lastToolSemanticSummary("已找到Q2预算数据")
                        .toolCallHistoryRefs(List.of("ref-1"))
                        .build())
                .recentMessages(List.of(
                        Map.of("role", "USER", "content_text", "继续上次季度复盘"),
                        Map.of("role", "ASSISTANT", "content_text", "我先收敛核心目标")
                ))
                .taskContext(Map.of("working_memory", Map.of("goal_refined", "完成Q2经营复盘")))
                .build();

        InputReconstructionResult result = agent.reconstruct(
                "s-1",
                "明天前给我一个可执行版本",
                contextPackage,
                TaskRuntimeState.PLANNING,
                RelationalRuntimeState.LIGHT_CHAT
        );

        assertEquals("完成Q2经营复盘", result.getExplicitTaskGoal());
        assertEquals("tomorrow", result.getTimeScope());
        assertTrue(result.getNormalizedUserIntent().contains("recentDialog="));
        assertTrue(result.getReformulatedQueryForRag().contains("goal=完成Q2经营复盘"));
    }
}

