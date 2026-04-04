package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.state.model.ContextState;
import org.yilena.luna.state.model.RecoveryState;
import org.yilena.luna.state.model.RetrievalState;
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
        ArgumentCaptor<RecoveryState> stateCaptor = ArgumentCaptor.forClass(RecoveryState.class);
        verify(recoveryStateStore).save(org.mockito.ArgumentMatchers.eq("s-1"), stateCaptor.capture());
        assertEquals("APPROVAL_RESUME", stateCaptor.getValue().getRecoveryEvent());
    }

    private GeminiProperty geminiProperty() {
        GeminiProperty property = new GeminiProperty();
        GeminiProperty.ModelConfig flash = new GeminiProperty.ModelConfig();
        flash.setModelName("test-flash");
        property.setFlash(flash);
        return property;
    }
}
