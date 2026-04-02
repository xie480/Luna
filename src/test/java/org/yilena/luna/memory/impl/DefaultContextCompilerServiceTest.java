package org.yilena.luna.memory.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.MemoryHotLayerService;
import org.yilena.luna.memory.RelationalMemoryRetriever;
import org.yilena.luna.memory.ResponseSynthesizerService;
import org.yilena.luna.memory.RuntimeRetriever;
import org.yilena.luna.memory.SocialReasonerService;
import org.yilena.luna.memory.TaskMemoryRetriever;
import org.yilena.luna.router.CapabilityPolicyRouterService;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.store.ContextStateStore;
import org.yilena.luna.state.store.RecoveryStateStore;
import org.yilena.luna.state.store.RetrievalStateStore;
import org.yilena.luna.state.store.TaskStateStore;
import org.yilena.luna.state.store.ToolStateStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultContextCompilerServiceTest {

    @Test
    void shouldRouteRetrievalByContextualSignalInsteadOfRawInput() {
        RuntimeRetriever runtimeRetriever = mock(RuntimeRetriever.class);
        TaskMemoryRetriever taskMemoryRetriever = mock(TaskMemoryRetriever.class);
        RelationalMemoryRetriever relationalMemoryRetriever = mock(RelationalMemoryRetriever.class);
        MemoryHotLayerService hotLayerService = mock(MemoryHotLayerService.class);
        SocialReasonerService socialReasonerService = mock(SocialReasonerService.class);
        ResponseSynthesizerService responseSynthesizerService = mock(ResponseSynthesizerService.class);
        CapabilityPolicyRouterService capabilityPolicyRouterService = mock(CapabilityPolicyRouterService.class);
        TaskStateStore taskStateStore = mock(TaskStateStore.class);
        RetrievalStateStore retrievalStateStore = mock(RetrievalStateStore.class);
        ToolStateStore toolStateStore = mock(ToolStateStore.class);
        ContextStateStore contextStateStore = mock(ContextStateStore.class);
        RecoveryStateStore recoveryStateStore = mock(RecoveryStateStore.class);

        when(hotLayerService.getCompiledContextCache(any(), any(), any(), any())).thenReturn(null);
        when(runtimeRetriever.retrieve("s-1")).thenReturn(Map.of(
                "recent_messages", List.of(Map.of("role", "USER", "content_text", "请继续上次任务")),
                "session", Map.of("session_type", "TASK")
        ));
        when(taskStateStore.load("s-1")).thenReturn(TaskState.builder()
                .taskId("p-1")
                .sessionId("s-1")
                .objective("输出季度复盘报告")
                .currentStage("PLANNING")
                .currentNode("node-3")
                .confirmedSlots(Map.of("quarter", "Q2"))
                .pendingQuestions(List.of("是否包含预算偏差"))
                .finishedSteps(List.of())
                .failedSteps(List.of())
                .retryCount(0)
                .nextActionHint("continue")
                .build());
        when(taskMemoryRetriever.retrieve(eq("s-1"), any(), eq(TaskRuntimeState.PLANNING))).thenReturn(Map.of());
        when(relationalMemoryRetriever.retrieve(eq("s-1"), any(), eq(RelationalRuntimeState.LIGHT_CHAT))).thenReturn(Map.of());
        when(capabilityPolicyRouterService.routeForContext(eq("s-1"), any(), eq(TaskRuntimeState.PLANNING), eq(RelationalRuntimeState.LIGHT_CHAT), anyInt()))
                .thenReturn(List.of());
        when(socialReasonerService.buildRelationalDraft(any(), any(), any(), any())).thenReturn(Map.of());
        when(responseSynthesizerService.buildSynthesisPolicy(any(), any(), any(), any(), any())).thenReturn(Map.of());

        DefaultContextCompilerService service = new DefaultContextCompilerService(
                runtimeRetriever,
                taskMemoryRetriever,
                relationalMemoryRetriever,
                hotLayerService,
                socialReasonerService,
                responseSynthesizerService,
                capabilityPolicyRouterService,
                taskStateStore,
                retrievalStateStore,
                toolStateStore,
                contextStateStore,
                recoveryStateStore
        );

        String rawInput = "只保留这个原文";
        service.compile("s-1", rawInput, TaskRuntimeState.PLANNING, RelationalRuntimeState.LIGHT_CHAT);

        ArgumentCaptor<String> signalCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(taskMemoryRetriever).retrieve(eq("s-1"), signalCaptor.capture(), eq(TaskRuntimeState.PLANNING));
        String signal = signalCaptor.getValue();

        assertNotEquals(rawInput, signal);
        assertTrue(signal.contains("输出季度复盘报告"));
        assertTrue(signal.contains("PLANNING"));
    }
}

