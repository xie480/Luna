package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.yilena.luna.context.StateTransitionTraceLogger;
import org.yilena.luna.context.ToolSemanticAgent;
import org.yilena.luna.context.ToolSemanticResultValidator;
import org.yilena.luna.context.ToolSemanticTraceLogger;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.service.TaskOrchestratorService;
import org.yilena.luna.service.model.RoundToolSemanticRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoundPipelineOrchestratorImplTest {

    @Test
    void shouldKeepRawChannelOnlyWhenSmallAgentTranslationFails() {
        TaskOrchestratorService taskOrchestratorService = mock(TaskOrchestratorService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TaskOrchestratorService> taskOrchestratorServiceProvider = mock(ObjectProvider.class);
        ToolSemanticAgent toolSemanticAgent = mock(ToolSemanticAgent.class);
        ToolSemanticResultValidator validator = mock(ToolSemanticResultValidator.class);
        ToolSemanticTraceLogger traceLogger = mock(ToolSemanticTraceLogger.class);
        RuntimeAuditService runtimeAuditService = mock(RuntimeAuditService.class);
        StateTransitionTraceLogger stateTransitionTraceLogger = mock(StateTransitionTraceLogger.class);

        when(taskOrchestratorServiceProvider.getObject()).thenReturn(taskOrchestratorService);
        when(toolSemanticAgent.translate(any(), any(), any(), any(), any())).thenThrow(new RuntimeException("agent timeout"));
        when(validator.validate(any(), any())).thenAnswer(invocation -> {
            ToolSemanticResult result = invocation.getArgument(0);
            return new ToolSemanticResultValidator.ValidationResult(true, java.util.List.of(), result);
        });

        RoundPipelineOrchestratorImpl orchestrator = new RoundPipelineOrchestratorImpl(
                taskOrchestratorServiceProvider,
                toolSemanticAgent,
                validator,
                traceLogger,
                runtimeAuditService,
                new ObjectMapper(),
                stateTransitionTraceLogger
        );

        ToolSemanticResult result = orchestrator.resolveToolSemantic(
                RoundToolSemanticRequest.builder()
                        .sessionId("s1")
                        .toolName("demo_tool")
                        .toolDescription("desc")
                        .toolContext("{\"status\":\"failed\",\"code\":429}")
                        .stage("ROUND")
                        .rawToolResultChannel(Map.of("latestToolRawRef", "tool_execution_trace:id=1"))
                        .build()
        );

        assertEquals("UNKNOWN", result.getToolStatus());
        assertEquals("semantic_translation_unavailable_raw_channel_only", result.getBusinessImpact());
        assertTrue(Boolean.TRUE.equals(result.getSemanticPayload().get("raw_channel_only")));
        assertTrue(Boolean.TRUE.equals(result.getSemanticPayload().get("semantic_translation_failed")));
    }
}
