package org.yilena.luna.memory.impl;

import org.junit.jupiter.api.Test;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.MemoryHotLayerService;
import org.yilena.luna.memory.RelationalMemoryRetriever;
import org.yilena.luna.memory.ResponseSynthesizerService;
import org.yilena.luna.memory.RuntimeRetriever;
import org.yilena.luna.memory.SocialReasonerService;
import org.yilena.luna.memory.TaskMemoryRetriever;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.state.store.ContextStateStore;
import org.yilena.luna.state.store.RecoveryStateStore;
import org.yilena.luna.state.store.RetrievalStateStore;
import org.yilena.luna.state.store.TaskStateStore;
import org.yilena.luna.state.store.ToolStateStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultContextCompilerServiceTest {

    @Test
    void shouldCompileRuntimeStateWithoutMemoryOrCapabilityPrefetch() {
        RuntimeRetriever runtimeRetriever = mock(RuntimeRetriever.class);
        MemoryHotLayerService hotLayerService = mock(MemoryHotLayerService.class);
        SocialReasonerService socialReasonerService = mock(SocialReasonerService.class);
        ResponseSynthesizerService responseSynthesizerService = mock(ResponseSynthesizerService.class);
        TaskMemoryRetriever taskMemoryRetriever = mock(TaskMemoryRetriever.class);
        RelationalMemoryRetriever relationalMemoryRetriever = mock(RelationalMemoryRetriever.class);
        TaskStateStore taskStateStore = mock(TaskStateStore.class);
        RetrievalStateStore retrievalStateStore = mock(RetrievalStateStore.class);
        ToolStateStore toolStateStore = mock(ToolStateStore.class);
        ContextStateStore contextStateStore = mock(ContextStateStore.class);
        RecoveryStateStore recoveryStateStore = mock(RecoveryStateStore.class);

        when(hotLayerService.getCompiledContextCache(any(), any(), any(), any())).thenReturn(null);
        when(runtimeRetriever.retrieve("s-1")).thenReturn(Map.of(
                "recent_messages", List.of(Map.of("role", "USER", "content_text", "继续上次任务")),
                "session", Map.of("session_type", "TASK", "current_goal", "输出季度复盘报告", "current_plan_id", "p-1")
        ));
        when(socialReasonerService.buildRelationalDraft(any(), any(), any(), any())).thenReturn(Map.of());
        when(responseSynthesizerService.buildSynthesisPolicy(any(), any(), any(), any(), any())).thenReturn(Map.of());

        DefaultContextCompilerService service = new DefaultContextCompilerService(
                runtimeRetriever,
                hotLayerService,
                taskMemoryRetriever,
                relationalMemoryRetriever,
                socialReasonerService,
                responseSynthesizerService,
                taskStateStore,
                retrievalStateStore,
                toolStateStore,
                contextStateStore,
                recoveryStateStore
        );

        StructuredContextPackage contextPackage = service.compile(
                "s-1",
                "原始输入",
                TaskRuntimeState.PLANNING,
                RelationalRuntimeState.LIGHT_CHAT
        );

        assertNotNull(contextPackage);
        assertTrue(contextPackage.getTaskContext().isEmpty());
        assertTrue(contextPackage.getRelationalContext().isEmpty());
        assertTrue(contextPackage.getCapabilityCandidates().isEmpty());
        assertEquals("ENTRY_PRELOADED_PLUS_NODE_ON_DEMAND", contextPackage.getPromptPolicy().get("memory_fetch_mode"));
        assertEquals("MCP_QUERY_ON_DEMAND", contextPackage.getPromptPolicy().get("capability_fetch_mode"));
        assertEquals("输出季度复盘报告", contextPackage.getTaskStateEntity().getObjective());
    }
}
