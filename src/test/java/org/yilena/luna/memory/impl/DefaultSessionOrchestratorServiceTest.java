package org.yilena.luna.memory.impl;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.mapper.SessionRuntimeMapper;
import org.yilena.luna.memory.ContextCompilerService;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultSessionOrchestratorServiceTest {

    @Test
    void shouldKeepCurrentTaskStateWhenStructuredSignalCompleteEvenWithPlanningNoise() {
        DefaultSessionOrchestratorService service = createService(
                TaskRuntimeState.EXECUTING,
                RelationalRuntimeState.TRUST_BUILDING
        );

        OrchestrationDecision decision = service.onUserInput(
                "s-1",
                "用户原文包含大量噪声",
                "intent=同步当前执行进度;goal=这个计划只是背景描述;timeScope=unspecified;constraints=[];missingSlots=[];fallback=none"
        );

        assertEquals(TaskRuntimeState.EXECUTING, decision.getTaskState());
    }

    @Test
    void shouldUseStructuredFallbackStateForPartialReconstructionSignals() {
        DefaultSessionOrchestratorService service = createService(
                TaskRuntimeState.WAITING_TOOL,
                RelationalRuntimeState.LIGHT_CHAT
        );

        OrchestrationDecision decision = service.onUserInput(
                "s-1",
                "继续",
                "intent=intent_unavailable;goal=goal_unavailable;timeScope=unspecified;constraints=[];missingSlots=[];fallback=reconstruction_partial"
        );

        assertEquals(TaskRuntimeState.CONTEXT_BUILDING, decision.getTaskState());
    }

    @Test
    void shouldAvoidRelationalKeywordDriftFromStructuredGoalText() {
        DefaultSessionOrchestratorService service = createService(
                TaskRuntimeState.EXECUTING,
                RelationalRuntimeState.TRUST_BUILDING
        );

        OrchestrationDecision decision = service.onUserInput(
                "s-1",
                "继续",
                "intent=同步任务状态;goal=结果让我很开心但仍需执行;timeScope=unspecified;constraints=[];missingSlots=[];fallback=none"
        );

        assertEquals(RelationalRuntimeState.TRUST_BUILDING, decision.getRelationalState());
    }

    @Test
    void strictGovernedSignalModeShouldIgnoreRawKeywordStateDrift() throws Exception {
        DefaultSessionOrchestratorService service = createService(
                TaskRuntimeState.EXECUTING,
                RelationalRuntimeState.TRUST_BUILDING
        );
        setBooleanField(service, "strictGovernedSignalMode", true);

        OrchestrationDecision decision = service.onUserInput(
                "s-1",
                "done finish completed",
                "intent=intent_unavailable;goal=goal_unavailable;timeScope=unspecified;constraints=[];missingSlots=[];fallback=reconstruction_partial"
        );

        assertEquals(TaskRuntimeState.CONTEXT_BUILDING, decision.getTaskState());
    }

    private DefaultSessionOrchestratorService createService(TaskRuntimeState previousTaskState,
                                                            RelationalRuntimeState previousRelationalState) {
        SessionRuntimeMapper sessionRuntimeMapper = mock(SessionRuntimeMapper.class);
        ContextCompilerService contextCompilerService = mock(ContextCompilerService.class);
        SessionTypeResolver sessionTypeResolver = mock(SessionTypeResolver.class);

        String sessionId = "s-1";
        when(sessionRuntimeMapper.selectTaskState(eq(sessionId))).thenReturn(previousTaskState.name());
        when(sessionRuntimeMapper.selectRelationalState(eq(sessionId))).thenReturn(previousRelationalState.name());
        when(sessionRuntimeMapper.selectSessionType(eq(sessionId))).thenReturn("HYBRID");
        when(sessionRuntimeMapper.selectLatestPlanRuntimeBySession(eq(sessionId))).thenReturn(Map.of());
        when(sessionRuntimeMapper.selectDefaultAgentId()).thenReturn(1L);
        when(contextCompilerService.compile(eq(sessionId), anyString(), any(), any()))
                .thenAnswer(invocation -> StructuredContextPackage.builder()
                        .sessionId(sessionId)
                        .taskState(invocation.getArgument(2, TaskRuntimeState.class))
                        .relationalState(invocation.getArgument(3, RelationalRuntimeState.class))
                        .build());

        return new DefaultSessionOrchestratorService(sessionRuntimeMapper, contextCompilerService, sessionTypeResolver, new ObjectMapper());
    }

    private void setBooleanField(Object target, String fieldName, boolean value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }
}
