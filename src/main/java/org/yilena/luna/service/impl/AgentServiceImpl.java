package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.adapter.LlmAdapter;
import org.yilena.luna.common.utils.JsonSchemaValidator;
import org.yilena.luna.entity.ChatMessage;
import org.yilena.luna.entity.ExecutionResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.executor.SkillExecutor;
import org.yilena.luna.gate.ExecutionGate;
import org.yilena.luna.gate.ToolExecutionGateway;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.router.CapabilityPolicyRouterService;
import org.yilena.luna.router.ToolRouter;
import org.yilena.luna.service.AgentService;
import org.yilena.luna.service.McpService;
import org.yilena.luna.service.PlanOrchestratorService;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.utils.AuthContextHolder;
import org.yilena.luna.utils.ToolCallingContextHolder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private final ToolRouter toolRouter;
    private final LlmAdapter llmAdapter;
    private final ExecutionGate executionGate;
    private final ToolExecutionGateway toolExecutionGateway;
    private final SkillExecutor skillExecutor;
    private final McpService mcpService;
    private final ObjectMapper objectMapper;
    private final SessionService sessionService;
    private final CapabilityPolicyRouterService capabilityPolicyRouterService;
    private final PlanOrchestratorService planOrchestratorService;

    @Override
    public String processToolCalling(String sessionId, String input) {
        return processToolCalling(sessionId, input, null, null);
    }

    @Override
    public String processToolCalling(String sessionId,
                                     String input,
                                     TaskRuntimeState taskState,
                                     RelationalRuntimeState relationalState) {
        log.info("processToolCalling, sessionId={}, input={}", sessionId, input);

        if (capabilityPolicyRouterService.shouldTriggerPlanOrchestration(input, taskState)) {
            String stableSessionId = resolveStableSessionId(sessionId);
            return planOrchestratorService.createAndRunPlan(stableSessionId, input, false);
        }

        List<Resource> candidates = toolRouter.findCandidates(input, taskState, relationalState);
        if (candidates.isEmpty()) {
            return null;
        }

        List<String> history = loadRecentHistory(sessionId);
        String decisionJson = llmAdapter.generate(buildDecisionPrompt(input, history, candidates));
        String targetName = parseToolName(decisionJson);
        if (targetName == null || "none".equalsIgnoreCase(targetName) || "null".equalsIgnoreCase(targetName)) {
            return null;
        }

        Resource target = candidates.stream()
                .filter(r -> targetName.equals(r.getName()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return null;
        }

        String generatedArgsJson = llmAdapter.generate(buildArgsPrompt(input, history, target));
        if (!JsonSchemaValidator.validate(target.getInputSchema(), generatedArgsJson)) {
            generatedArgsJson = llmAdapter.generate(String.format(PromptTemplates.TOOL_ARGS_REPAIR_PROMPT, target.getInputSchema(), generatedArgsJson));
        }
        final String argsJson = generatedArgsJson;

        executionGate.check(target);

        if (ResourceType.WORKFLOW.equals(target.getType()) || ResourceType.SKILL.equals(target.getType())) {
            return runAndTrace(target, argsJson, () -> skillExecutor.execute(target, argsJson));
        }
        if (ResourceType.PROMPT.equals(target.getType())) {
            return runAndTrace(target, argsJson, () -> toJson(Map.of(
                    "status", "success",
                    "data", mcpService.getPrompt(target.getServerCode(), target.getName(), argsJson)
            )));
        }
        if (ResourceType.RESOURCE.equals(target.getType())) {
            String uri = target.getResourceUri() == null ? target.getName() : target.getResourceUri();
            return runAndTrace(target, argsJson, () -> toJson(Map.of(
                    "status", "success",
                    "data", mcpService.readResource(target.getServerCode(), uri)
            )));
        }
        if (ResourceType.STRATEGY.equals(target.getType())
                && capabilityPolicyRouterService.shouldTriggerPlanOrchestration(input, taskState)) {
            String stableSessionId = resolveStableSessionId(sessionId);
            return runAndTrace(target, argsJson, () -> planOrchestratorService.createAndRunPlan(stableSessionId, input, false));
        }

        String stableSessionId = resolveStableSessionId(sessionId);
        long startAt = System.currentTimeMillis();
        try {
            ExecutionResult result = toolExecutionGateway.executeTool(stableSessionId, target, argsJson);
            String output = result.getRawResult() != null
                    ? result.getRawResult()
                    : toJson(Map.of(
                    "status", result.getStatus(),
                    "message", result.getMessage(),
                    "data", result.getData()
            ));
            String status = result.getStatus() == null || result.getStatus().isBlank()
                    ? parseStatusFromOutput(output, "SUCCESS")
                    : result.getStatus();
            recordToolExecutionTrace(target, argsJson, output, status, result.getMessage(), System.currentTimeMillis() - startAt);
            return output;
        } catch (Exception ex) {
            recordToolExecutionTrace(target, argsJson, null, "FAILED", ex.getMessage(), System.currentTimeMillis() - startAt);
            throw ex;
        }
    }

    private String runAndTrace(Resource target, String argsJson, TraceSupplier supplier) {
        long startAt = System.currentTimeMillis();
        try {
            String output = supplier.get();
            recordToolExecutionTrace(target, argsJson, output, parseStatusFromOutput(output, "SUCCESS"), null, System.currentTimeMillis() - startAt);
            return output;
        } catch (Exception ex) {
            recordToolExecutionTrace(target, argsJson, null, "FAILED", ex.getMessage(), System.currentTimeMillis() - startAt);
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("capability execution failed", ex);
        }
    }

    private String resolveStableSessionId(String sessionId) {
        String jwtJti = AuthContextHolder.getSessionId();
        if (jwtJti != null && !jwtJti.isBlank()) {
            return jwtJti;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return "agent-default";
        }
        return sessionId;
    }

    private String buildDecisionPrompt(String input, List<String> history, List<Resource> tools) {
        String toolDesc = tools.stream()
                .map(t -> "- " + t.getName() + ": " + t.getDescription())
                .collect(Collectors.joining("\n"));
        String historyText = (history == null || history.isEmpty()) ? "(empty)" : String.join("\n", history);
        return String.format(PromptTemplates.TOOL_DECISION_PROMPT, input, historyText, toolDesc);
    }

    private String buildArgsPrompt(String input, List<String> history, Resource resource) {
        String historyText = (history == null || history.isEmpty()) ? "(empty)" : String.join("\n", history);
        if (ResourceType.WORKFLOW.equals(resource.getType()) || ResourceType.SKILL.equals(resource.getType())) {
            return String.format(
                    PromptTemplates.SKILL_ARGS_PROMPT,
                    input,
                    historyText,
                    resource.getName(),
                    resource.getDescription(),
                    resource.getInputSchema(),
                    buildWorkflowHint(resource)
            );
        }
        return String.format(
                PromptTemplates.TOOL_ARGS_PROMPT,
                input,
                historyText,
                resource.getName(),
                resource.getDescription(),
                resource.getInputSchema()
        );
    }

    private String buildWorkflowHint(Resource resource) {
        String capabilities = resource.getRequiredCapabilities() == null ? "[]" : resource.getRequiredCapabilities().toString();
        String slots = resource.getToolSlots() == null ? "[]" : resource.getToolSlots().stream()
                .map(s -> "{slot=" + s.getSlot() + ", capability=" + s.getCapability() + ", required=" + s.getRequired() + "}")
                .collect(Collectors.joining(", ", "[", "]"));
        String thoughtChain = resource.getThoughtChain() == null ? "[]" : resource.getThoughtChain().toString();
        return "requiredCapabilities=" + capabilities + ";toolSlots=" + slots + ";thoughtChain=" + thoughtChain;
    }

    private List<String> loadRecentHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<ChatMessage> recent = sessionService.getRecentMessages(sessionId, false);
            if (recent == null || recent.isEmpty()) {
                return Collections.emptyList();
            }
            int from = Math.max(0, recent.size() - 20);
            return recent.subList(from, recent.size()).stream()
                    .map(m -> m.getRole().name() + ": " + m.getContent())
                    .toList();
        } catch (Exception e) {
            log.warn("load history failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String parseToolName(String json) {
        if (json == null) {
            return null;
        }
        try {
            String clean = json.trim().replace("```json", "").replace("```", "");
            JsonNode node = objectMapper.readTree(clean);
            if (node.has("tool_name")) {
                return node.get("tool_name").asText();
            }
        } catch (Exception e) {
            log.warn("parse tool decision failed: {}", json);
        }
        return null;
    }

    private void recordToolExecutionTrace(Resource target,
                                          String argsJson,
                                          String output,
                                          String status,
                                          String error,
                                          long latencyMs) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("tool_name", target == null ? "unknown_tool" : target.getName());
        trace.put("call_status", normalizeStatus(status));
        trace.put("source_type", target == null || target.getType() == null ? "UNKNOWN" : target.getType().name());
        trace.put("normalized_input", safeToTraceObject(argsJson));
        trace.put("normalized_output", safeToTraceObject(output));
        trace.put("error_message", error == null ? "" : error);
        trace.put("latency_ms", Math.max(0L, latencyMs));
        ToolCallingContextHolder.appendToolExecutionTrace(trace);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "UNKNOWN";
        }
        return status.trim().toUpperCase();
    }

    private String parseStatusFromOutput(String output, String fallback) {
        if (output == null || output.isBlank()) {
            return fallback;
        }
        try {
            JsonNode node = objectMapper.readTree(output);
            String status = node.path("status").asText("");
            if (status.isBlank()) {
                return fallback;
            }
            return status.toUpperCase();
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private Object safeToTraceObject(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignore) {
            return Map.of("raw", text);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"serialization failed\"}";
        }
    }

    @FunctionalInterface
    private interface TraceSupplier {
        String get() throws Exception;
    }
}
