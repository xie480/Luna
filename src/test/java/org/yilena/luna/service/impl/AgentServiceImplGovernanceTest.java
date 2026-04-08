package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yilena.luna.adapter.LlmAdapter;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.gate.ExecutionGate;
import org.yilena.luna.gate.ToolExecutionGateway;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.router.CapabilityPolicyRouterService;
import org.yilena.luna.router.ToolRouter;
import org.yilena.luna.prompt.governance.PromptResolverService;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;
import org.yilena.luna.service.McpService;
import org.yilena.luna.service.PlanOrchestratorService;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.service.model.ToolDecisionCommand;
import org.yilena.luna.executor.WorkflowExecutor;
import org.yilena.luna.utils.ToolDecisionInputSignatureUtil;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceImplGovernanceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldRejectWhenAssembledDecisionContextMissingAndAudit() throws Exception {
        RuntimeAuditService runtimeAuditService = mock(RuntimeAuditService.class);
        AgentServiceImpl service = newService(runtimeAuditService, mock(ToolRouter.class), mock(LlmAdapter.class));
        String sessionId = "s-1";
        String decisionInput = "goal=run";

        ToolDecisionCommand command = ToolDecisionCommand.builder()
                .sessionId(sessionId)
                .rawUserInput("raw")
                .toolDecisionInput(decisionInput)
                .governedInputSignature("")
                .assembledDecisionContext("")
                .executionCandidates(List.of())
                .build();

        String result = service.processToolCallingWithGovernance(command);

        assertNull(result);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(runtimeAuditService).persistDecisionRecord(
                eq(sessionId),
                eq(null),
                eq(null),
                eq("UNGOVERNED_TOOL_DECISION_REJECTED"),
                eq("missing_assembled_decision_context"),
                payloadCaptor.capture()
        );
        JsonNode payload = OBJECT_MAPPER.readTree(payloadCaptor.getValue());
        assertFalse(payload.path("hasAssembledContext").asBoolean(true));
        assertEquals(0, payload.path("assembledContextLength").asInt(-1));
        assertFalse(payload.path("hasSignature").asBoolean(true));
    }

    @Test
    void shouldStillRejectWhenSignatureValidButAssembledDecisionContextMissing() {
        RuntimeAuditService runtimeAuditService = mock(RuntimeAuditService.class);
        AgentServiceImpl service = newService(runtimeAuditService, mock(ToolRouter.class), mock(LlmAdapter.class));
        String sessionId = "s-2";
        String decisionInput = "goal=run";
        String signature = ToolDecisionInputSignatureUtil.sign(sessionId, decisionInput, "");

        ToolDecisionCommand command = ToolDecisionCommand.builder()
                .sessionId(sessionId)
                .rawUserInput("raw")
                .toolDecisionInput(decisionInput)
                .governedInputSignature(signature)
                .assembledDecisionContext("")
                .executionCandidates(List.of())
                .build();

        String result = service.processToolCallingWithGovernance(command);

        assertNull(result);
        verify(runtimeAuditService).persistDecisionRecord(
                eq(sessionId),
                eq(null),
                eq(null),
                eq("UNGOVERNED_TOOL_DECISION_REJECTED"),
                eq("missing_assembled_decision_context"),
                anyString()
        );
    }

    @Test
    void shouldProceedToolDecisionWhenAssembledDecisionContextPresent() {
        RuntimeAuditService runtimeAuditService = mock(RuntimeAuditService.class);
        ToolRouter toolRouter = mock(ToolRouter.class);
        LlmAdapter llmAdapter = mock(LlmAdapter.class);
        when(llmAdapter.generate(anyString())).thenReturn("{\"action_type\":\"none\",\"target_name\":\"none\"}");
        AgentServiceImpl service = newService(runtimeAuditService, toolRouter, llmAdapter);

        String sessionId = "s-3";
        String decisionInput = "goal=do_work|task_stage=executing";
        String assembled = "governed workset";
        String signature = ToolDecisionInputSignatureUtil.sign(sessionId, decisionInput, assembled);
        Resource candidate = Resource.builder()
                .type(ResourceType.TOOL)
                .name("tool.demo")
                .description("demo")
                .inputSchema("{\"type\":\"object\"}")
                .build();

        ToolDecisionCommand command = ToolDecisionCommand.builder()
                .sessionId(sessionId)
                .rawUserInput("raw_input_should_not_be_used")
                .toolDecisionInput(decisionInput)
                .governedInputSignature(signature)
                .assembledDecisionContext(assembled)
                .executionCandidates(List.of(candidate))
                .build();

        String result = service.processToolCallingWithGovernance(command);

        assertNull(result);
        verify(runtimeAuditService, never()).persistDecisionRecord(
                anyString(),
                any(),
                any(),
                eq("UNGOVERNED_TOOL_DECISION_REJECTED"),
                anyString(),
                anyString()
        );
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmAdapter).generate(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("Assembled Decision Workset"));
        assertTrue(promptCaptor.getValue().contains(assembled));
    }

    @Test
    void shouldPassGovernanceContextToToolDecisionResolver() throws Exception {
        RuntimeAuditService runtimeAuditService = mock(RuntimeAuditService.class);
        ToolRouter toolRouter = mock(ToolRouter.class);
        LlmAdapter llmAdapter = mock(LlmAdapter.class);
        PromptResolverService promptResolverService = mock(PromptResolverService.class);
        when(llmAdapter.generate(anyString())).thenReturn("{\"action_type\":\"none\",\"target_name\":\"none\"}");
        when(promptResolverService.resolve(any(PromptResolveContext.class)))
                .thenReturn(PromptResolveResult.builder().slotMapping(Map.of()).build());
        AgentServiceImpl service = newService(runtimeAuditService, toolRouter, llmAdapter);
        setField(service, "promptResolverService", promptResolverService);

        String sessionId = "s-4";
        String decisionInput = "goal=do_work|task_stage=executing";
        String assembled = "governed workset";
        String signature = ToolDecisionInputSignatureUtil.sign(sessionId, decisionInput, assembled);
        Resource candidate = Resource.builder()
                .type(ResourceType.TOOL)
                .name("tool.demo")
                .description("demo")
                .inputSchema("{\"type\":\"object\"}")
                .build();
        ToolDecisionCommand command = ToolDecisionCommand.builder()
                .sessionId(sessionId)
                .rawUserInput("raw_input_should_not_be_used")
                .toolDecisionInput(decisionInput)
                .policyId("policy-chat-v1")
                .personaId("persona-maid-v1")
                .sceneId("scene-night-v1")
                .taskState(null)
                .modelFamily("qwen")
                .governedInputSignature(signature)
                .assembledDecisionContext(assembled)
                .executionCandidates(List.of(candidate))
                .build();

        service.processToolCallingWithGovernance(command);

        ArgumentCaptor<PromptResolveContext> contextCaptor = ArgumentCaptor.forClass(PromptResolveContext.class);
        verify(promptResolverService).resolve(contextCaptor.capture());
        PromptResolveContext resolveContext = contextCaptor.getValue();
        assertEquals("policy-chat-v1", resolveContext.getPolicyId());
        assertEquals("persona-maid-v1", resolveContext.getPersonaId());
        assertEquals("scene-night-v1", resolveContext.getSceneId());
        assertEquals("qwen", resolveContext.getModelFamily());
        assertEquals("TOOL_DECISION_AGENT", resolveContext.getAgent());
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private AgentServiceImpl newService(RuntimeAuditService runtimeAuditService, ToolRouter toolRouter, LlmAdapter llmAdapter) {
        return new AgentServiceImpl(
                toolRouter,
                llmAdapter,
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
