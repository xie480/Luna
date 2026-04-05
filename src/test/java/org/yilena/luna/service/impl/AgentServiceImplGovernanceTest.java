package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.yilena.luna.adapter.LlmAdapter;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.entity.ToolCallingContext;
import org.yilena.luna.gate.ExecutionGate;
import org.yilena.luna.gate.ToolExecutionGateway;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.router.CapabilityPolicyRouterService;
import org.yilena.luna.router.ToolRouter;
import org.yilena.luna.service.McpService;
import org.yilena.luna.service.PlanOrchestratorService;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.executor.WorkflowExecutor;
import org.yilena.luna.utils.ToolCallingContextHolder;
import org.yilena.luna.utils.ToolDecisionInputSignatureUtil;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceImplGovernanceTest {

    @AfterEach
    void tearDown() {
        ToolCallingContextHolder.clear();
    }

    @Test
    void shouldRejectDecisionInputWhenMissingGovernedContext() {
        RuntimeAuditService runtimeAuditService = mock(RuntimeAuditService.class);
        AgentServiceImpl service = newService(runtimeAuditService);

        String result = service.processToolCalling("s-1", "task_stage=PLANNING");

        assertNull(result);
        verify(runtimeAuditService).persistDecisionRecord(
                eq("s-1"),
                eq(null),
                eq(null),
                eq("UNGOVERNED_TOOL_DECISION_REJECTED"),
                eq("missing_tool_calling_context"),
                anyString()
        );
    }

    @Test
    void shouldRejectDecisionInputWhenSignatureInvalid() {
        RuntimeAuditService runtimeAuditService = mock(RuntimeAuditService.class);
        AgentServiceImpl service = newService(runtimeAuditService);
        ToolCallingContextHolder.set(ToolCallingContext.builder()
                .chatSessionKey("s-2")
                .toolDecisionInput("goal=run")
                .governedInputSignature("invalid-signature")
                .assembledDecisionContext("assembled")
                .executionCandidates(List.<Resource>of())
                .toolExecutionTraces(new CopyOnWriteArrayList<>())
                .build());

        String result = service.processToolCalling("s-2", "goal=run");

        assertNull(result);
        verify(runtimeAuditService).persistDecisionRecord(
                eq("s-2"),
                eq(null),
                eq(null),
                eq("UNGOVERNED_TOOL_DECISION_REJECTED"),
                eq("invalid_governed_input_signature"),
                anyString()
        );
    }

    @Test
    void shouldAcceptDecisionInputOnlyFromSignedGovernedContext() {
        RuntimeAuditService runtimeAuditService = mock(RuntimeAuditService.class);
        ToolRouter toolRouter = mock(ToolRouter.class);
        when(toolRouter.findCandidates(anyString(), any(), any())).thenReturn(List.of());
        AgentServiceImpl service = newService(runtimeAuditService, toolRouter);

        String sessionId = "s-3";
        String decisionInput = "goal=do_work|task_stage=executing";
        String assembled = "governed workset";
        ToolCallingContextHolder.set(ToolCallingContext.builder()
                .chatSessionKey(sessionId)
                .toolDecisionInput(decisionInput)
                .governedInputSignature(ToolDecisionInputSignatureUtil.sign(sessionId, decisionInput, assembled))
                .assembledDecisionContext(assembled)
                .executionCandidates(List.<Resource>of())
                .toolExecutionTraces(new CopyOnWriteArrayList<>())
                .build());

        String result = service.processToolCalling(sessionId, "raw_input_should_not_be_used");

        assertNull(result);
        verify(runtimeAuditService, never()).persistDecisionRecord(
                anyString(),
                any(),
                any(),
                eq("UNGOVERNED_TOOL_DECISION_REJECTED"),
                anyString(),
                anyString()
        );
    }

    private AgentServiceImpl newService(RuntimeAuditService runtimeAuditService) {
        return newService(runtimeAuditService, mock(ToolRouter.class));
    }

    private AgentServiceImpl newService(RuntimeAuditService runtimeAuditService, ToolRouter toolRouter) {
        return new AgentServiceImpl(
                toolRouter,
                mock(LlmAdapter.class),
                mock(ExecutionGate.class),
                mock(ToolExecutionGateway.class),
                mock(WorkflowExecutor.class),
                mock(McpService.class),
                new ObjectMapper(),
                mock(SessionService.class),
                mock(CapabilityPolicyRouterService.class),
                mock(PlanOrchestratorService.class),
                runtimeAuditService
        );
    }
}
