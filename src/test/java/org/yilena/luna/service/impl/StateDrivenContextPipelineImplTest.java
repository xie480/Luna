package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.yilena.luna.context.StateTransitionTraceLogger;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.service.RoundPipelineOrchestrator;
import org.yilena.luna.service.TaskOrchestratorService;
import org.yilena.luna.service.model.RoundPipelineRequest;
import org.yilena.luna.service.model.RoundPipelineResult;
import org.yilena.luna.service.model.StateDrivenContextPipelineRequest;
import org.yilena.luna.service.model.TaskOrchestrationResult;
import org.yilena.luna.mapper.SessionRuntimeMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StateDrivenContextPipelineImplTest {

    @Test
    void shouldHardBlockWhenReconstructionMissingInHydration() {
        RoundPipelineOrchestrator roundPipelineOrchestrator = mock(RoundPipelineOrchestrator.class);
        RuntimeAuditService runtimeAuditService = mock(RuntimeAuditService.class);
        TaskOrchestratorService taskOrchestratorService = mock(TaskOrchestratorService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TaskOrchestratorService> taskOrchestratorServiceProvider = mock(ObjectProvider.class);
        SessionRuntimeMapper sessionRuntimeMapper = mock(SessionRuntimeMapper.class);
        StateTransitionTraceLogger stateTransitionTraceLogger = mock(StateTransitionTraceLogger.class);

        when(taskOrchestratorServiceProvider.getObject()).thenReturn(taskOrchestratorService);
        when(taskOrchestratorService.orchestrateUserInput(anyString(), anyString()))
                .thenReturn(TaskOrchestrationResult.builder().build());

        StateDrivenContextPipelineImpl pipeline = new StateDrivenContextPipelineImpl(
                roundPipelineOrchestrator,
                runtimeAuditService,
                taskOrchestratorServiceProvider,
                sessionRuntimeMapper,
                new ObjectMapper(),
                stateTransitionTraceLogger
        );

        RoundPipelineResult result = pipeline.run(
                StateDrivenContextPipelineRequest.builder()
                        .sessionId("s1")
                        .triggerSource("TEST")
                        .roundPipelineRequest(RoundPipelineRequest.builder()
                                .sessionId("s1")
                                .userInput("please continue")
                                .build())
                        .build()
        );

        assertTrue(result.isBlocked());
        assertEquals("state_driven_context_pipeline_hydration_failed", result.getBlockedReason());
        verify(roundPipelineOrchestrator, never()).executeRound(any());
    }
}
