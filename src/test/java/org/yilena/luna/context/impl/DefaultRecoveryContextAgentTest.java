package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.state.model.ContextState;
import org.yilena.luna.state.model.ContextSnapshot;
import org.yilena.luna.state.model.RecoveryState;
import org.yilena.luna.state.model.RetrievalState;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.store.ContextSnapshotStore;
import org.yilena.luna.state.store.RecoveryStateStore;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultRecoveryContextAgentTest {

    @Test
    void shouldMarkRefreshPlanAndPersistRecoveryStateOnFailureResume() {
        RecoveryStateStore recoveryStateStore = mock(RecoveryStateStore.class);
        ContextSnapshotStore contextSnapshotStore = mock(ContextSnapshotStore.class);
        when(contextSnapshotStore.loadLatest("s-1")).thenReturn(null);

        DefaultRecoveryContextAgent agent = new DefaultRecoveryContextAgent(
                recoveryStateStore,
                contextSnapshotStore,
                new ObjectMapper(),
                mock(LlmClientUtil.class),
                geminiProperty()
        );
        StructuredContextPackage contextPackage = StructuredContextPackage.builder()
                .sessionId("s-1")
                .taskState(TaskRuntimeState.WAITING_TOOL)
                .retrievalState(RetrievalState.builder()
                        .reconstructedIntent("intent")
                        .activeQueries(List.of("q1"))
                        .retrievalPlan(Map.of("seed", true))
                        .selectedEvidenceRefs(List.of("knowledge:1"))
                        .rerankSummary("")
                        .build())
                .contextState(ContextState.builder()
                        .activeKnowledgeRefs(List.of("knowledge:1"))
                        .activeMcpResourceRefs(List.of("search_knowledge"))
                        .activeMcpPromptRefs(List.of("prompt_a"))
                        .activeMemoryRefs(List.of())
                        .activeToolEvidenceRefs(List.of())
                        .latestStateSnapshot(Map.of())
                        .latestNarrativeSummary("")
                        .latestContextSnapshotId("")
                        .build())
                .recoveryState(RecoveryState.builder().recoverySnapshotId("").build())
                .build();

        StructuredContextPackage recovered = agent.recover(
                "s-1",
                contextPackage,
                "APPROVAL_RESUME",
                "TOOL_FAILED"
        );

        assertNotNull(recovered);
        assertNotNull(recovered.getRecoveryState());
        assertEquals("APPROVAL_RESUME", recovered.getRecoveryState().getRecoveryEvent());
        assertTrue(Boolean.TRUE.equals(recovered.getRetrievalState().getRetrievalPlan().get("need_mcp_refresh")));
        assertTrue(Boolean.TRUE.equals(recovered.getRetrievalState().getRetrievalPlan().get("need_reassembly")));
        assertTrue(Boolean.TRUE.equals(recovered.getRetrievalState().getRetrievalPlan().get("refresh_mcp_now")));
        assertTrue(Boolean.TRUE.equals(recovered.getRetrievalState().getRetrievalPlan().get("reassemble_now")));
        ArgumentCaptor<RecoveryState> stateCaptor = ArgumentCaptor.forClass(RecoveryState.class);
        verify(recoveryStateStore).save(org.mockito.ArgumentMatchers.eq("s-1"), stateCaptor.capture());
        assertEquals("APPROVAL_RESUME", stateCaptor.getValue().getRecoveryEvent());
    }

    @Test
    void shouldReadActiveRefsFromFinalSnapshotForPreciseInvalidation() {
        RecoveryStateStore recoveryStateStore = mock(RecoveryStateStore.class);
        ContextSnapshotStore contextSnapshotStore = mock(ContextSnapshotStore.class);
        when(contextSnapshotStore.loadLatest("s-2")).thenReturn(ContextSnapshot.builder()
                .snapshotId("42")
                .sessionId("s-2")
                .planId(1L)
                .nodeId(2L)
                .payload(Map.of(
                        "snapshotType", "FINAL_MODEL_CONTEXT",
                        "activeKnowledgeRefs", List.of("knowledge:block-1"),
                        "activeMcpPromptRefs", List.of("{\"capability_name\":\"prompt.cap.alpha\"}"),
                        "activeMcpResourceRefs", List.of("{\"capability_name\":\"tool.search_knowledge\"}")
                ))
                .build());

        DefaultRecoveryContextAgent agent = new DefaultRecoveryContextAgent(
                recoveryStateStore,
                contextSnapshotStore,
                new ObjectMapper(),
                mock(LlmClientUtil.class),
                geminiProperty()
        );
        StructuredContextPackage contextPackage = StructuredContextPackage.builder()
                .sessionId("s-2")
                .taskState(TaskRuntimeState.WAITING_TOOL)
                .retrievalState(RetrievalState.builder()
                        .reconstructedIntent("intent")
                        .activeQueries(List.of("q1"))
                        .retrievalPlan(Map.of())
                        .selectedEvidenceRefs(List.of())
                        .rerankSummary("")
                        .build())
                .contextState(ContextState.builder()
                        .activeKnowledgeRefs(List.of())
                        .activeMcpResourceRefs(List.of())
                        .activeMcpPromptRefs(List.of())
                        .activeMemoryRefs(List.of())
                        .activeToolEvidenceRefs(List.of())
                        .latestStateSnapshot(Map.of())
                        .latestNarrativeSummary("")
                        .latestContextSnapshotId("")
                        .build())
                .build();

        StructuredContextPackage recovered = agent.recover(
                "s-2",
                contextPackage,
                "TOOL_RESULT",
                "timeout"
        );

        assertNotNull(recovered);
        assertTrue(Boolean.TRUE.equals(recovered.getRetrievalState().getRetrievalPlan().get("need_rag_refresh")));
        assertTrue(Boolean.TRUE.equals(recovered.getRetrievalState().getRetrievalPlan().get("need_mcp_refresh")));
        assertTrue(Boolean.TRUE.equals(recovered.getRetrievalState().getRetrievalPlan().get("refresh_rag_now")));
        assertTrue(Boolean.TRUE.equals(recovered.getRetrievalState().getRetrievalPlan().get("refresh_mcp_now")));
        @SuppressWarnings("unchecked")
        List<String> invalidatedEvidenceRefs = (List<String>) recovered.getRetrievalState().getRetrievalPlan().get("invalidated_evidence_refs");
        @SuppressWarnings("unchecked")
        List<String> invalidatedCapabilityNames = (List<String>) recovered.getRetrievalState().getRetrievalPlan().get("invalidated_capability_names");
        assertTrue(invalidatedEvidenceRefs.contains("knowledge:block-1"));
        assertTrue(invalidatedCapabilityNames.contains("prompt.cap.alpha"));
        assertTrue(invalidatedCapabilityNames.contains("tool.search_knowledge"));
    }

    @Test
    void shouldTriggerRecoveryRefreshWithChineseReasonKeywords() {
        RecoveryStateStore recoveryStateStore = mock(RecoveryStateStore.class);
        ContextSnapshotStore contextSnapshotStore = mock(ContextSnapshotStore.class);
        when(contextSnapshotStore.loadLatest("s-cn")).thenReturn(null);

        DefaultRecoveryContextAgent agent = new DefaultRecoveryContextAgent(
                recoveryStateStore,
                contextSnapshotStore,
                new ObjectMapper(),
                mock(LlmClientUtil.class),
                geminiProperty()
        );
        StructuredContextPackage contextPackage = StructuredContextPackage.builder()
                .sessionId("s-cn")
                .taskState(TaskRuntimeState.WAITING_TOOL)
                .retrievalState(RetrievalState.builder()
                        .reconstructedIntent("intent")
                        .activeQueries(List.of("q1"))
                        .retrievalPlan(Map.of())
                        .selectedEvidenceRefs(List.of("knowledge:1"))
                        .rerankSummary("")
                        .build())
                .contextState(ContextState.builder()
                        .activeKnowledgeRefs(List.of("knowledge:1"))
                        .activeMcpResourceRefs(List.of("search_knowledge"))
                        .activeMcpPromptRefs(List.of("prompt_a"))
                        .activeMemoryRefs(List.of())
                        .activeToolEvidenceRefs(List.of())
                        .latestStateSnapshot(Map.of())
                        .latestNarrativeSummary("")
                        .latestContextSnapshotId("")
                        .build())
                .build();

        StructuredContextPackage recovered = agent.recover(
                "s-cn",
                contextPackage,
                "TOOL_RESULT",
                "工具执行超时且结果冲突"
        );

        assertNotNull(recovered);
        assertTrue(Boolean.TRUE.equals(recovered.getRetrievalState().getRetrievalPlan().get("need_rag_refresh")));
        assertTrue(Boolean.TRUE.equals(recovered.getRetrievalState().getRetrievalPlan().get("need_mcp_refresh")));
        assertTrue(Boolean.TRUE.equals(recovered.getRetrievalState().getRetrievalPlan().get("need_reassembly")));
    }

    @Test
    void shouldPreferStructuredRecoveryPayloadFromFinalSnapshot() {
        RecoveryStateStore recoveryStateStore = mock(RecoveryStateStore.class);
        ContextSnapshotStore contextSnapshotStore = mock(ContextSnapshotStore.class);
        when(contextSnapshotStore.loadLatest("s-structured")).thenReturn(ContextSnapshot.builder()
                .snapshotId("901")
                .sessionId("s-structured")
                .planId(8L)
                .nodeId(9L)
                .payload(Map.of(
                        "snapshotType", "FINAL_MODEL_CONTEXT",
                        "structuredRecoveryPayload", Map.of(
                                "taskState", Map.of("taskId", "t-901", "currentNode", "9", "currentStage", "EXECUTING"),
                                "retrievalState", Map.of(
                                        "reconstructedIntent", "intent-901",
                                        "retrievalPlan", Map.of("refreshRagNow", true)
                                ),
                                "contextState", Map.of(
                                        "latestNarrativeSummary", "summary",
                                        "latestStateSnapshot", Map.of(),
                                        "activeKnowledgeRefs", List.of("knowledge:901"),
                                        "latestContextSnapshotId", "901"
                                ),
                                "runtimePointers", Map.of("snapshotId", "901", "nodeId", 9)
                        )
                ))
                .build());
        DefaultRecoveryContextAgent agent = new DefaultRecoveryContextAgent(
                recoveryStateStore,
                contextSnapshotStore,
                new ObjectMapper(),
                mock(LlmClientUtil.class),
                geminiProperty()
        );
        StructuredContextPackage recovered = agent.recover(
                "s-structured",
                StructuredContextPackage.builder().sessionId("s-structured").build(),
                "APPROVAL_RESUME",
                "timeout"
        );
        assertNotNull(recovered);
        assertNotNull(recovered.getTaskStateEntity());
        assertEquals("t-901", recovered.getTaskStateEntity().getTaskId());
        assertNotNull(recovered.getContextState());
        assertTrue(recovered.getContextState().getActiveKnowledgeRefs().contains("knowledge:901"));
    }

    @Test
    void shouldForceReassembleWhenRecoveryFlagsInconsistent() {
        RecoveryStateStore recoveryStateStore = mock(RecoveryStateStore.class);
        ContextSnapshotStore contextSnapshotStore = mock(ContextSnapshotStore.class);
        when(contextSnapshotStore.loadLatest("s-mismatch")).thenReturn(ContextSnapshot.builder()
                .snapshotId("77")
                .sessionId("s-mismatch")
                .planId(3L)
                .nodeId(4L)
                .payload(Map.of(
                        "snapshotType", "FINAL_MODEL_CONTEXT",
                        "structuredRecoveryPayload", Map.of(
                                "taskState", Map.of("taskId", "task-1", "currentNode", "4"),
                                "retrievalState", Map.of(
                                        "reconstructedIntent", "intent",
                                        "retrievalPlan", Map.of(
                                                "refreshRagNow", false,
                                                "refreshMcpNow", false,
                                                "reassembleNow", false
                                        )
                                ),
                                "contextState", Map.of(
                                        "latestNarrativeSummary", "",
                                        "latestStateSnapshot", Map.of(),
                                        "activeKnowledgeRefs", List.of("knowledge:4"),
                                        "latestContextSnapshotId", "77"
                                )
                        )
                ))
                .build());

        DefaultRecoveryContextAgent agent = new DefaultRecoveryContextAgent(
                recoveryStateStore,
                contextSnapshotStore,
                new ObjectMapper(),
                mock(LlmClientUtil.class),
                geminiProperty()
        );
        StructuredContextPackage current = StructuredContextPackage.builder()
                .sessionId("s-mismatch")
                .taskStateEntity(TaskState.builder().taskId("task-1").currentNode("4").build())
                .retrievalState(RetrievalState.builder()
                        .reconstructedIntent("intent")
                        .activeQueries(List.of("q"))
                        .retrievalPlan(Map.of("refreshRagNow", true, "refreshMcpNow", true, "reassembleNow", true))
                        .selectedEvidenceRefs(List.of("knowledge:4"))
                        .rerankSummary("")
                        .build())
                .build();

        StructuredContextPackage recovered = agent.recover(
                "s-mismatch",
                current,
                "TOOL_RESULT",
                "timeout"
        );
        assertNotNull(recovered);
        assertNotNull(recovered.getRetrievalState());
        assertTrue(Boolean.TRUE.equals(recovered.getRetrievalState().getRetrievalPlan().get("reassembleNow")));
    }

    private GeminiProperty geminiProperty() {
        GeminiProperty property = new GeminiProperty();
        GeminiProperty.ModelConfig flash = new GeminiProperty.ModelConfig();
        flash.setModelName("test-flash");
        property.setFlash(flash);
        return property;
    }
}
