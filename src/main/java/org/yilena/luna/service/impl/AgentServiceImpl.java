package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.PromptResolverService;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;
import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;
import org.yilena.luna.router.CapabilityPolicyRouterService;
import org.yilena.luna.router.ToolRouter;
import org.yilena.luna.service.AgentService;
import org.yilena.luna.service.McpService;
import org.yilena.luna.service.PlanOrchestratorService;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.service.model.ToolDecisionCommand;
import org.yilena.luna.utils.AuthContextHolder;
import org.yilena.luna.utils.ToolDecisionInputSignatureUtil;
import org.yilena.luna.utils.ToolCallingContextHolder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 工具决策代理服务实现，负责在治理上下文约束下选择能力、生成参数并驱动工具或工作流执行。
 */
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
    @Autowired(required = false)
    private PromptRegistryService promptRegistryService;
    @Autowired(required = false)
    private PromptResolverService promptResolverService;
    @Value("${luna.governance.strict-tool-decision:true}")
    private boolean strictToolDecision = true;
    private static final String TOOL_DECISION_PROMPT_FALLBACK = """
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
            %s
            """;

    @Override
    public String processToolCallingWithGovernance(ToolDecisionCommand command) {
        /**
         * 先解析稳定会话并校验治理上下文，只有带签名且上下文完整的请求才允许进入工具决策。
         */
        String sessionId = resolveStableSessionId(command == null ? null : command.getSessionId());
        if (!validateGovernedDecisionContext(sessionId, command, true)) {
            return null;
        }
        String input = command.getRawUserInput();
        String assembledDecisionContext = command.getAssembledDecisionContext();
        TaskRuntimeState taskState = command.getTaskState();
        RelationalRuntimeState relationalState = command.getRelationalState();
        List<Resource> executionCandidates = command.getExecutionCandidates();
        log.info("processToolCallingWithGovernance, sessionId={}, input={}", sessionId, input);
        String decisionInput = resolveDecisionInput(sessionId, command);
        if (decisionInput.isBlank()) {
            log.info("skip tool decision: governed decision input unavailable");
            return null;
        }

        /**
         * 对于被策略识别为复杂任务的输入，优先切换到计划编排链路，而不是继续走单次工具调用。
         */
        if (capabilityPolicyRouterService.shouldTriggerPlanOrchestration(decisionInput, taskState)) {
            return planOrchestratorService.createAndRunPlan(sessionId, decisionInput, false);
        }

        /**
         * 先确定候选能力集合，没有可执行候选时直接返回，让上游决定是否走纯文本回复。
         */
        List<Resource> candidates = executionCandidates == null || executionCandidates.isEmpty()
                ? toolRouter.findCandidates(decisionInput, taskState, relationalState)
                : executionCandidates;
        if (candidates.isEmpty()) {
            return null;
        }

        /**
         * 基于近期历史和组装后的决策工作集调用模型，产出本轮动作类型、目标能力和参数草案。
         */
        List<String> history = loadRecentHistory(sessionId);
        String decisionJson = llmAdapter.generate(buildDecisionPrompt(command, assembledDecisionContext));
        DecisionAction decision = parseDecisionAction(decisionJson);
        if (decision == null || "none".equalsIgnoreCase(decision.targetName()) || "null".equalsIgnoreCase(decision.targetName())) {
            return null;
        }
        if ("direct_answer".equals(decision.actionType())) {
            return decision.directAnswer();
        }

        /**
         * 将模型返回的目标名称映射到真实候选资源，避免模型幻觉导致执行到不存在的能力。
         */
        Resource target = resolveTarget(candidates, decision);
        if (target == null) {
            return null;
        }

        /**
         * 参数缺失时补做一次参数生成，并在 schema 不匹配时触发修复 Prompt，保证执行参数可用。
         */
        String generatedArgsJson = decision.argumentsJson();
        if (generatedArgsJson == null || generatedArgsJson.isBlank()) {
            generatedArgsJson = llmAdapter.generate(buildArgsPrompt(command, decisionInput, history, target));
        }
        if (!JsonSchemaValidator.validate(target.getInputSchema(), generatedArgsJson)) {
            generatedArgsJson = llmAdapter.generate(String.format(
                    resolvePrompt("tool.args.repair.json_v1", PromptTemplates.TOOL_ARGS_REPAIR_PROMPT),
                    target.getInputSchema(),
                    generatedArgsJson
            ));
        }
        final String argsJson = generatedArgsJson;

        /**
         * 执行前统一走能力闸门校验，避免高风险能力绕过审批和权限控制。
         */
        executionGate.check(target);

        /**
         * 根据目标资源类型分流到工作流、Prompt、资源读取或工具执行链路，并统一记录执行轨迹。
         */
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
            return runAndTrace(target, argsJson, () -> planOrchestratorService.createAndRunPlan(sessionId, decisionInput, false));
        }

        long startAt = System.currentTimeMillis();
        try {
            /**
             * 普通工具统一通过执行网关调用，并把输出、状态和耗时写入工具追踪通道。
             */
            ExecutionResult result = toolExecutionGateway.executeTool(sessionId, target, argsJson);
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
            /**
             * 对非工具网关分支统一包裹追踪逻辑，保证所有能力调用都有一致的审计轨迹。
             */
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

    private String resolveDecisionInput(String sessionId, ToolDecisionCommand command) {
        /**
         * 再次校验治理输入，并把候选能力补写到线程上下文，供后续执行轨迹复用。
         */
        if (!validateGovernedDecisionContext(sessionId, command, false)) {
            return "";
        }
        String decisionInput = command.getToolDecisionInput().trim();
        ToolCallingContext holderContext = ToolCallingContextHolder.get();
        if (holderContext != null && holderContext.getExecutionCandidates() == null) {
            holderContext.setExecutionCandidates(command.getExecutionCandidates());
        }
        return decisionInput;
    }

    private boolean validateGovernedDecisionContext(String sessionId, ToolDecisionCommand command, boolean auditOnFailure) {
        /**
         * 统一治理校验出口，失败时按需记录审计日志，成功时才允许继续进入决策流程。
         */
        GovernedDecisionRejectReason rejectReason = resolveRejectReason(sessionId, command);
        if (rejectReason == null) {
            return true;
        }
        if (auditOnFailure) {
            auditUngovernedDecisionRejected(sessionId, rejectReason, command);
        }
        return false;
    }

    private GovernedDecisionRejectReason resolveRejectReason(String sessionId, ToolDecisionCommand command) {
        if (command == null) {
            return GovernedDecisionRejectReason.EMPTY_GOVERNED_DECISION_INPUT;
        }
        if (!strictToolDecision) {
            return null;
        }
        String assembledDecisionContext = command.getAssembledDecisionContext();
        if (assembledDecisionContext == null || assembledDecisionContext.isBlank()) {
            return GovernedDecisionRejectReason.MISSING_ASSEMBLED_DECISION_CONTEXT;
        }
        String decisionInput = command.getToolDecisionInput() == null ? "" : command.getToolDecisionInput().trim();
        if (decisionInput.isBlank()) {
            return GovernedDecisionRejectReason.EMPTY_GOVERNED_DECISION_INPUT;
        }
        boolean signatureValid = ToolDecisionInputSignatureUtil.verify(
                command.getGovernedInputSignature(),
                sessionId,
                decisionInput,
                assembledDecisionContext
        );
        if (!signatureValid) {
            return GovernedDecisionRejectReason.INVALID_GOVERNED_INPUT_SIGNATURE;
        }
        return null;
    }

    private void auditUngovernedDecisionRejected(String sessionId, GovernedDecisionRejectReason reason, ToolDecisionCommand command) {
        String rawInput = command == null ? "" : command.getRawUserInput();
        String assembledDecisionContext = command == null || command.getAssembledDecisionContext() == null
                ? ""
                : command.getAssembledDecisionContext();
        boolean hasSignature = command != null
                && command.getGovernedInputSignature() != null
                && !command.getGovernedInputSignature().isBlank();
        log.warn("tool decision input blocked: {}", reason.code());
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                null,
                null,
                "UNGOVERNED_TOOL_DECISION_REJECTED",
                reason.code(),
                toJson(Map.of(
                        "reason", reason.code(),
                        "hasAssembledContext", !assembledDecisionContext.isBlank(),
                        "assembledContextLength", assembledDecisionContext.length(),
                        "hasSignature", hasSignature,
                        "rawInputPreview", rawInput == null ? "" : rawInput.substring(0, Math.min(rawInput.length(), 240))
                ))
        );
    }

    private String buildDecisionPrompt(ToolDecisionCommand command, String assembledDecisionContext) {
        /**
         * 优先读取治理中心中的决策 Prompt 模板，确保工具选择逻辑可按策略动态调整。
         */
        String template = resolveToolDecisionPrompt(command, "agent.tool_decision", "tool.decision.default_v1", TOOL_DECISION_PROMPT_FALLBACK);
        String workset = assembledDecisionContext == null ? "" : assembledDecisionContext;
        if (template.contains("%s")) {
            return template.replace("%s", workset);
        }
        return template + System.lineSeparator() + workset;
    }

    private String buildArgsPrompt(ToolDecisionCommand command, String input, List<String> history, Resource resource) {
        /**
         * 参数生成 Prompt 会根据资源类型切换模板，工作流会附带能力槽位和思维链提示。
         */
        String historyText = (history == null || history.isEmpty()) ? "(empty)" : String.join("\n", history);
        if (ResourceType.WORKFLOW.equals(resource.getType())) {
            return String.format(
                    resolveToolDecisionPrompt(command, "agent.workflow_args", "workflow.args.default_v1", PromptTemplates.SKILL_ARGS_PROMPT),
                    input,
                    historyText,
                    resource.getName(),
                    resource.getDescription(),
                    resource.getInputSchema(),
                    buildWorkflowHint(resource)
            );
        }
        return String.format(
                resolveToolDecisionPrompt(command, "agent.tool_args", "tool.args.default_v1", PromptTemplates.TOOL_ARGS_PROMPT),
                input,
                historyText,
                resource.getName(),
                resource.getDescription(),
                resource.getInputSchema()
        );
    }

    private String resolveToolDecisionPrompt(ToolDecisionCommand command,
                                             String runtimeSlot,
                                             String fallbackKey,
                                             String fallbackValue) {
        if (promptResolverService != null) {
            try {
                PromptResolveResult resolved = promptResolverService.resolve(PromptResolveContext.builder()
                        .sessionId(resolveStableSessionId(command == null ? null : command.getSessionId()))
                        .userInput(command == null ? "" : command.getToolDecisionInput())
                        .policyId(command == null ? "" : command.getPolicyId())
                        .manualPromptKeys(normalizePromptKeys(command == null ? null : command.getManualPromptKeys()))
                        .personaId(command == null ? "" : command.getPersonaId())
                        .sceneId(command == null ? "" : command.getSceneId())
                        .agent("TOOL_DECISION_AGENT")
                        .nodeKind("TOOL_DECISION")
                        .taskState(command == null || command.getTaskState() == null ? "" : command.getTaskState().name())
                        .modelFamily(command == null ? "" : command.getModelFamily())
                        .build());
                String fromSlot = firstResolvedPromptValue(resolved, runtimeSlot);
                if (!fromSlot.isBlank()) {
                    return fromSlot;
                }
            } catch (Exception ignore) {
            }
        }
        return resolvePrompt(fallbackKey, fallbackValue);
    }

    private String firstResolvedPromptValue(PromptResolveResult resolved, String runtimeSlot) {
        if (resolved == null || resolved.getSlotMapping() == null || runtimeSlot == null || runtimeSlot.isBlank()) {
            return "";
        }
        List<ResolvedPromptItem> items = resolved.getSlotMapping().get(runtimeSlot);
        if (items == null || items.isEmpty()) {
            return "";
        }
        for (ResolvedPromptItem item : items) {
            if (item != null && item.getValue() != null && !item.getValue().isBlank()) {
                return item.getValue();
            }
        }
        return "";
    }

    private List<String> normalizePromptKeys(List<String> rawKeys) {
        if (rawKeys == null || rawKeys.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (String rawKey : rawKeys) {
            if (rawKey == null) {
                continue;
            }
            String key = rawKey.trim();
            if (!key.isBlank() && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private String resolvePrompt(String key, String fallback) {
        if (promptRegistryService == null) {
            return fallback;
        }
        return promptRegistryService.resolvePromptValue(key, fallback);
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
            /**
             * 兼容模型输出中的多种字段命名和 Markdown 包装，尽量归一化为统一动作对象。
             */
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
        /**
         * 先按目标名称和期望类型精确匹配，匹配不到时再退回名称级兜底，提高容错性。
         */
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
        /**
         * 将调用输入、输出、状态和耗时统一收集到线程上下文，供后续语义翻译和审计落库使用。
         */
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

    private enum GovernedDecisionRejectReason {
        EMPTY_GOVERNED_DECISION_INPUT("empty_governed_decision_input"),
        INVALID_GOVERNED_INPUT_SIGNATURE("invalid_governed_input_signature"),
        MISSING_ASSEMBLED_DECISION_CONTEXT("missing_assembled_decision_context");

        private final String code;

        GovernedDecisionRejectReason(String code) {
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}

