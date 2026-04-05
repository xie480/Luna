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
import org.yilena.luna.entity.ToolCallingContext;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.executor.WorkflowExecutor;
import org.yilena.luna.gate.ExecutionGate;
import org.yilena.luna.gate.ToolExecutionGateway;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.router.CapabilityPolicyRouterService;
import org.yilena.luna.router.ToolRouter;
import org.yilena.luna.service.AgentService;
import org.yilena.luna.service.McpService;
import org.yilena.luna.service.PlanOrchestratorService;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.utils.AuthContextHolder;
import org.yilena.luna.utils.ToolDecisionInputSignatureUtil;
import org.yilena.luna.utils.ToolCallingContextHolder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private final ToolRouter toolRouter;
    private final LlmAdapter llmAdapter;
    private final ExecutionGate executionGate;
    private final ToolExecutionGateway toolExecutionGateway;
    private final WorkflowExecutor workflowExecutor;
    private final McpService mcpService;
    private final ObjectMapper objectMapper;
    private final SessionService sessionService;
    private final CapabilityPolicyRouterService capabilityPolicyRouterService;
    private final PlanOrchestratorService planOrchestratorService;
    private final RuntimeAuditService runtimeAuditService;
    private static final String WORKFLOW_ARGS_PROMPT_TEMPLATE = PromptTemplates.SKILL_ARGS_PROMPT;

    @Override
    public String processToolCalling(String sessionId, String input) {
        return processToolCalling(sessionId, input, null, null);
    }

    @Override
    public String processToolCalling(String sessionId,
                                     String input,
                                     TaskRuntimeState taskState,
                                     RelationalRuntimeState relationalState) {
        return processToolCalling(sessionId, input, taskState, relationalState, null);
    }

    @Override
    public String processToolCalling(String sessionId,
                                     String input,
                                     TaskRuntimeState taskState,
                                     RelationalRuntimeState relationalState,
                                     List<Resource> executionCandidates) {
        return processToolCalling(sessionId, input, taskState, relationalState, executionCandidates, null);
    }

    @Override
    public String processToolCalling(String sessionId,
                                     String input,
                                     TaskRuntimeState taskState,
                                     RelationalRuntimeState relationalState,
                                     List<Resource> executionCandidates,
                                     String assembledDecisionContext) {
        log.info("processToolCalling, sessionId={}, input={}", sessionId, input);
        String decisionInput = resolveDecisionInput(sessionId, input);
        if (decisionInput.isBlank()) {
            log.info("skip tool decision: governed decision input unavailable");
            return null;
        }

        if (capabilityPolicyRouterService.shouldTriggerPlanOrchestration(decisionInput, taskState)) {
            String stableSessionId = resolveStableSessionId(sessionId);
            return planOrchestratorService.createAndRunPlan(stableSessionId, decisionInput, false);
        }

        List<Resource> candidates = executionCandidates == null || executionCandidates.isEmpty()
                ? toolRouter.findCandidates(decisionInput, taskState, relationalState)
                : executionCandidates;
        if (candidates.isEmpty()) {
            return null;
        }

        List<String> history = loadRecentHistory(sessionId);
        String decisionJson = llmAdapter.generate(buildDecisionPrompt(decisionInput, history, candidates, assembledDecisionContext));
        DecisionAction decision = parseDecisionAction(decisionJson);
        if (decision == null || "none".equalsIgnoreCase(decision.targetName()) || "null".equalsIgnoreCase(decision.targetName())) {
            return null;
        }
        if ("direct_answer".equals(decision.actionType())) {
            return decision.directAnswer();
        }

        Resource target = resolveTarget(candidates, decision);
        if (target == null) {
            return null;
        }

        String generatedArgsJson = decision.argumentsJson();
        if (generatedArgsJson == null || generatedArgsJson.isBlank()) {
            generatedArgsJson = llmAdapter.generate(buildArgsPrompt(decisionInput, history, target));
        }
        if (!JsonSchemaValidator.validate(target.getInputSchema(), generatedArgsJson)) {
            generatedArgsJson = llmAdapter.generate(String.format(PromptTemplates.TOOL_ARGS_REPAIR_PROMPT, target.getInputSchema(), generatedArgsJson));
        }
        final String argsJson = generatedArgsJson;

        executionGate.check(target);

        if (ResourceType.WORKFLOW.equals(target.getType())) {
            return runAndTrace(target, argsJson, () -> workflowExecutor.execute(target, argsJson));
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
                && capabilityPolicyRouterService.shouldTriggerPlanOrchestration(decisionInput, taskState)) {
            String stableSessionId = resolveStableSessionId(sessionId);
            return runAndTrace(target, argsJson, () -> planOrchestratorService.createAndRunPlan(stableSessionId, decisionInput, false));
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

    private String resolveDecisionInput(String sessionId, String input) {
        ToolCallingContext context = ToolCallingContextHolder.get();
        if (context == null) {
            auditUngovernedDecisionRejected(resolveStableSessionId(sessionId), "missing_tool_calling_context", input);
            return "";
        }
        String decisionInput = context.getToolDecisionInput() == null ? "" : context.getToolDecisionInput().trim();
        if (decisionInput.isBlank()) {
            auditUngovernedDecisionRejected(resolveStableSessionId(sessionId), "empty_governed_decision_input", input);
            return "";
        }
        boolean signatureValid = ToolDecisionInputSignatureUtil.verify(
                context.getGovernedInputSignature(),
                context.getChatSessionKey(),
                decisionInput,
                context.getAssembledDecisionContext()
        );
        if (!signatureValid) {
            auditUngovernedDecisionRejected(resolveStableSessionId(sessionId), "invalid_governed_input_signature", input);
            return "";
        }
        return decisionInput;
    }

    private void auditUngovernedDecisionRejected(String sessionId, String reason, String rawInput) {
        log.warn("tool decision input blocked: {}", reason);
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                null,
                null,
                "UNGOVERNED_TOOL_DECISION_REJECTED",
                reason,
                toJson(Map.of(
                        "reason", reason,
                        "rawInputPreview", rawInput == null ? "" : rawInput.substring(0, Math.min(rawInput.length(), 240))
                ))
        );
    }

    private String buildDecisionPrompt(String input, List<String> history, List<Resource> tools, String assembledDecisionContext) {
        if (assembledDecisionContext != null && !assembledDecisionContext.isBlank()) {
            return """
                    You are a tool decision agent. Decide the next action strictly from the assembled decision workset.
                    The workset already contains node state, MCP hints, constraints and recent tool semantics.
                    Return exactly one JSON object, no markdown.

                    Action JSON:
                    {"action_type":"tool_call|prompt_get|resource_read|workflow_start|direct_answer","target_name":"...","arguments":{...}}
                    or
                    {"action_type":"direct_answer","answer":"..."}
                    or
                    {"action_type":"none","target_name":"none"}

                    Assembled Decision Workset:
                    """ + assembledDecisionContext;
        }
        String toolDesc = tools.stream()
                .map(this::formatCandidateForDecisionPrompt)
                .collect(Collectors.joining("\n"));
        String historyText = (history == null || history.isEmpty()) ? "(empty)" : String.join("\n", history);
        String base = String.format(PromptTemplates.TOOL_DECISION_PROMPT, input, historyText, toolDesc);
        return base + """

                动作协议输出要求（严格）：
                1) 返回单个 JSON，不要 markdown；
                2) 优先输出：
                   {"action_type":"tool_call|prompt_get|resource_read|workflow_start|direct_answer","target_name":"...","arguments":{...}}
                3) direct_answer 时输出：
                   {"action_type":"direct_answer","answer":"..."}
                4) 兼容旧格式可返回 {"tool_name":"..."}，系统会自动视为 tool_call。
                """;
    }

    private String formatCandidateForDecisionPrompt(Resource resource) {
        String type = resource == null || resource.getType() == null ? "TOOL" : resource.getType().name();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", resource == null ? "" : resource.getName());
        payload.put("type", type);
        payload.put("description", resource == null ? "" : resource.getDescription());
        payload.put("server_code", resource == null ? "" : resource.getServerCode());
        payload.put("execution_mode", resource == null ? "MCP" : resource.getExecutionMode());
        payload.put("input_schema", parseJsonOrText(resource == null ? null : resource.getInputSchema()));
        payload.put("arguments_schema", parseJsonOrText(resource == null ? null : resource.getArgumentsSchema()));
        payload.put("approval_required", resource != null && Boolean.TRUE.equals(resource.getRequiresApproval()));
        payload.put("sensitivity", resource == null || resource.getSensitivity() == null ? "LOW" : resource.getSensitivity().name());
        payload.put("resource_uri", resource == null ? null : resource.getResourceUri());
        payload.put("mime_type", resource == null ? null : resource.getMimeType());
        return "- " + toJson(payload);
    }

    private String buildArgsPrompt(String input, List<String> history, Resource resource) {
        String historyText = (history == null || history.isEmpty()) ? "(empty)" : String.join("\n", history);
        if (ResourceType.WORKFLOW.equals(resource.getType())) {
            return String.format(
                    WORKFLOW_ARGS_PROMPT_TEMPLATE,
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

    private DecisionAction parseDecisionAction(String json) {
        if (json == null) {
            return null;
        }
        try {
            String clean = json.trim().replace("```json", "").replace("```", "");
            JsonNode node = objectMapper.readTree(clean);
            String actionType = text(node, "action_type");
            if (actionType.isBlank()) {
                actionType = text(node, "action");
            }
            if (actionType.isBlank() && node.has("tool_name")) {
                actionType = "tool_call";
            }
            actionType = normalizeActionType(actionType);
            if (actionType == null) {
                return null;
            }

            if ("direct_answer".equals(actionType)) {
                String answer = text(node, "answer");
                if (answer.isBlank()) {
                    answer = text(node, "direct_answer");
                }
                if (answer.isBlank()) {
                    answer = text(node, "message");
                }
                return new DecisionAction(actionType, null, null, answer);
            }

            String targetName = text(node, "target_name");
            if (targetName.isBlank()) {
                targetName = text(node, "tool_name");
            }
            if (targetName.isBlank()) {
                targetName = text(node, "prompt_name");
            }
            if (targetName.isBlank()) {
                targetName = text(node, "resource_uri");
            }
            if (targetName.isBlank()) {
                targetName = text(node, "workflow_name");
            }
            String argumentsJson = null;
            JsonNode argsNode = node.get("arguments");
            if (argsNode != null && !argsNode.isNull()) {
                argumentsJson = argsNode.isTextual() ? argsNode.asText() : argsNode.toString();
            } else {
                String argsText = text(node, "arguments_json");
                if (!argsText.isBlank()) {
                    argumentsJson = argsText;
                }
            }
            return new DecisionAction(actionType, targetName, argumentsJson, null);
        } catch (Exception e) {
            log.warn("parse tool decision failed: {}", json);
        }
        return null;
    }

    private Resource resolveTarget(List<Resource> candidates, DecisionAction decision) {
        if (decision == null || candidates == null || candidates.isEmpty()) {
            return null;
        }
        String targetName = decision.targetName();
        if (targetName == null || targetName.isBlank()) {
            return null;
        }
        ResourceType expectedType = expectedResourceType(decision.actionType());
        return candidates.stream()
                .filter(r -> targetName.equals(r.getName()) || targetName.equals(r.getResourceUri()))
                .filter(r -> expectedType == null || expectedType.equals(r.getType()))
                .findFirst()
                .orElseGet(() -> candidates.stream()
                        .filter(r -> targetName.equals(r.getName()) || targetName.equals(r.getResourceUri()))
                        .findFirst()
                        .orElse(null));
    }

    private ResourceType expectedResourceType(String actionType) {
        if (actionType == null) {
            return null;
        }
        return switch (actionType) {
            case "tool_call" -> ResourceType.TOOL;
            case "prompt_get" -> ResourceType.PROMPT;
            case "resource_read" -> ResourceType.RESOURCE;
            case "workflow_start" -> ResourceType.WORKFLOW;
            default -> null;
        };
    }

    private String normalizeActionType(String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return null;
        }
        String normalized = actionType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "tool_call", "prompt_get", "resource_read", "workflow_start", "direct_answer" -> normalized;
            case "tool", "call_tool" -> "tool_call";
            case "prompt", "get_prompt" -> "prompt_get";
            case "resource", "read_resource" -> "resource_read";
            case "workflow", "start_workflow" -> "workflow_start";
            case "answer" -> "direct_answer";
            default -> null;
        };
    }

    private String text(JsonNode node, String field) {
        if (node == null || field == null || field.isBlank() || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText("");
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

    private Object parseJsonOrText(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignore) {
            return raw;
        }
    }

    @FunctionalInterface
    private interface TraceSupplier {
        String get() throws Exception;
    }

    private record DecisionAction(String actionType, String targetName, String argumentsJson, String directAnswer) {
    }
}
