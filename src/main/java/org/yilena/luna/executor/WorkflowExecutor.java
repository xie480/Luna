package org.yilena.luna.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.constants.RocketMqConstant;
import org.yilena.luna.entity.ExecutionResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.enums.RunMode;
import org.yilena.luna.gate.ToolExecutionGateway;
import org.yilena.luna.mq.dto.SkillExecutionMessage;
import org.yilena.luna.service.McpService;
import org.yilena.luna.sse.LunaStatusPublisher;
import org.yilena.luna.utils.AuthContextHolder;
import org.yilena.luna.utils.LlmClientUtil;
import org.yilena.luna.utils.ToolCallingContextHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowExecutor {

    private final RocketMQTemplate rocketMQTemplate;
    private final ToolExecutionGateway toolExecutionGateway;
    private final McpService mcpService;
    private final LlmClientUtil llmClientUtil;
    private final ObjectMapper objectMapper;
    private final LunaStatusPublisher statusPublisher;

    public String execute(Resource workflow, String argsJson) {
        log.info("Workflow dispatch start, workflowName={}, runMode={}", workflow.getName(), workflow.getRunMode());

        String validateErr = validateExecutableWorkflow(workflow);
        if (validateErr != null) {
            return validateErr;
        }

        if (RunMode.ASYNC.equals(workflow.getRunMode())) {
            String taskId = UUID.randomUUID().toString();
            log.info("Workflow async task created, taskId={}, workflowName={}", taskId, workflow.getName());

            statusPublisher.publish(
                    LunaStatusPublisher.DEFAULT_CLIENT_ID,
                    LunaStateConstant.STATUS_WORKING,
                    "Luna 正在后台执行工作流：" + workflow.getName()
            );

            SkillExecutionMessage msg = SkillExecutionMessage.builder()
                    .taskId(taskId)
                    .resource(workflow)
                    .argsJson(argsJson)
                    .build();

            rocketMQTemplate.convertAndSend(RocketMqConstant.TOPIC_WORKFLOW_ASYNC, msg);

            return String.format(
                    "{\"status\":\"pending\", \"taskId\":\"%s\", \"workflowName\":\"%s\", \"message\":\"异步任务已提交，后台执行中\"}",
                    taskId,
                    workflow.getName()
            );
        }

        return executeLoop(workflow, argsJson);
    }

    public String executeLoop(Resource workflow, String argsJson) {
        long start = System.currentTimeMillis();

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();

        result.put("workflowName", workflow.getName());
        result.put("runMode", workflow.getRunMode() != null ? workflow.getRunMode().name() : "SYNC");

        statusPublisher.publish(
                LunaStatusPublisher.DEFAULT_CLIENT_ID,
                LunaStateConstant.STATUS_WORKING,
                "Luna 正在执行工作流：" + workflow.getName()
        );

        try {
            JsonNode inputNode = parseArgs(argsJson);
            List<Resource.ToolSlotDto> slots = workflow.getToolSlots() != null ? workflow.getToolSlots() : Collections.emptyList();
            List<String> chain = workflow.getThoughtChain() != null ? workflow.getThoughtChain() : Collections.emptyList();

            if (slots.isEmpty()) {
                return error("WORKFLOW_CONFIG_INVALID", "工作流未配置 toolSlots，无法执行 step loop", workflow.getName(), null, null);
            }

            Map<String, Object> state = new LinkedHashMap<>();
            state.put("input", inputNode);

            int successCount = 0;
            int failCount = 0;

            for (int i = 0; i < slots.size(); i++) {
                Resource.ToolSlotDto slot = slots.get(i);
                String stepDesc = i < chain.size() ? chain.get(i) : ("step-" + (i + 1));
                long stepStart = System.currentTimeMillis();

                Map<String, Object> step = new LinkedHashMap<>();
                step.put("stepIndex", i + 1);
                step.put("slot", slot.getSlot());
                step.put("capability", slot.getCapability());
                step.put("thought", stepDesc);

                statusPublisher.publish(
                        LunaStatusPublisher.DEFAULT_CLIENT_ID,
                        LunaStateConstant.STATUS_THINKING,
                        String.format("Luna 正在执行工作流步骤 %d/%d：%s", i + 1, slots.size(), stepDesc)
                );

                Resource matchedTool = null;
                String toolArgs = "{}";
                try {
                    matchedTool = selectBestToolByCapability(slot.getCapability(), inputNode);
                    if (matchedTool == null) {
                        if (Boolean.TRUE.equals(slot.getRequired())) {
                            throw new IllegalStateException("未匹配到必需能力的工具: capability=" + slot.getCapability() + ", slot=" + slot.getSlot());
                        }
                        step.put("status", "SKIPPED");
                        step.put("message", "可选槽位未匹配到工具，已跳过");
                        step.put("costMs", System.currentTimeMillis() - stepStart);
                        stepResults.add(step);
                        continue;
                    }

                    step.put("toolName", matchedTool.getName());
                    step.put("toolId", matchedTool.getId());

                    toolArgs = buildToolArgs(inputNode, state);
                    step.put("toolArgs", safeToNode(toolArgs));

                    String sessionId = AuthContextHolder.getSessionId();
                    ExecutionResult execResult = toolExecutionGateway.executeTool(
                            sessionId == null ? "workflow-executor" : sessionId,
                            matchedTool,
                            toolArgs
                    );
                    String toolResult = execResult.getRawResult() == null ? safeJson(execResult) : execResult.getRawResult();
                    JsonNode toolResultNode = safeToNode(toolResult);
                    boolean stepSuccess = isToolSuccess(toolResultNode);

                    step.put("toolResult", toolResultNode != null ? toolResultNode : toolResult);
                    step.put("status", stepSuccess ? "SUCCESS" : "FAILED");
                    step.put("costMs", System.currentTimeMillis() - stepStart);
                    recordToolTrace(matchedTool, toolArgs, toolResult, stepSuccess ? "SUCCESS" : "FAILED", null, System.currentTimeMillis() - stepStart);

                    state.put(slot.getSlot(), toolResultNode != null ? toolResultNode : toolResult);
                    if (stepSuccess) {
                        successCount++;
                    } else {
                        failCount++;
                        if (Boolean.TRUE.equals(slot.getRequired())) {
                            stepResults.add(step);
                            break;
                        }
                    }
                } catch (Exception e) {
                    failCount++;
                    step.put("status", "FAILED");
                    step.put("error", e.getMessage());
                    step.put("costMs", System.currentTimeMillis() - stepStart);
                    recordToolTrace(matchedTool, toolArgs, null, "FAILED", e.getMessage(), System.currentTimeMillis() - stepStart);
                    stepResults.add(step);
                    if (Boolean.TRUE.equals(slot.getRequired())) {
                        break;
                    }
                    continue;
                }
                stepResults.add(step);
            }

            boolean hasRequiredFailure = stepResults.stream().anyMatch(s ->
                    "FAILED".equals(s.get("status")) && Boolean.TRUE.equals(findRequiredBySlot(slots, String.valueOf(s.get("slot")))));

            result.put("stepResults", stepResults);
            result.put("successSteps", successCount);
            result.put("failedSteps", failCount);
            result.put("costMs", System.currentTimeMillis() - start);
            result.put("status", hasRequiredFailure ? "error" : "success");
            result.put("state", state);

            if (hasRequiredFailure) {
                Map<String, Object> firstRequiredFailure = stepResults.stream()
                        .filter(s -> "FAILED".equals(s.get("status")) && Boolean.TRUE.equals(findRequiredBySlot(slots, String.valueOf(s.get("slot")))))
                        .findFirst()
                        .orElse(Map.of());
                result.put("errorCode", "WORKFLOW_TOOL_MISSING");
                result.put("message", "工作流执行失败：必需步骤缺少可用工具");
                result.put("missingToolSlot", String.valueOf(firstRequiredFailure.getOrDefault("slot", "")));
                result.put("missingCapability", String.valueOf(firstRequiredFailure.getOrDefault("capability", "")));
                statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, "工作流执行失败：" + workflow.getName());
                return objectMapper.writeValueAsString(result);
            }

            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, "工作流执行完成：" + workflow.getName());
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("Workflow execution failed, workflowName={}, err={}", workflow.getName(), e.getMessage(), e);
            return error("WORKFLOW_EXECUTION_EXCEPTION", "工作流执行异常: " + e.getMessage(), workflow.getName(), null, null);
        } finally {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
        }
    }

    private JsonNode parseArgs(String argsJson) throws Exception {
        String content = (argsJson == null || argsJson.isBlank()) ? "{}" : argsJson;
        return objectMapper.readTree(content);
    }

    private Boolean findRequiredBySlot(List<Resource.ToolSlotDto> slots, String slotName) {
        return slots.stream()
                .filter(s -> Objects.equals(s.getSlot(), slotName))
                .map(Resource.ToolSlotDto::getRequired)
                .findFirst()
                .orElse(false);
    }

    private boolean isToolSuccess(JsonNode node) {
        if (node == null || !node.isObject() || !node.has("status")) {
            return true;
        }
        String status = node.get("status").asText("");
        return "success".equalsIgnoreCase(status) || "ok".equalsIgnoreCase(status);
    }

    private Resource selectBestToolByCapability(String capability, JsonNode inputNode) throws Exception {
        if (capability == null || capability.isBlank()) {
            return null;
        }
        List<Resource> candidates = mcpService.searchResources(capability);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<Resource> tools = candidates.stream()
                .filter(r -> ResourceType.TOOL.equals(r.getType()))
                .collect(Collectors.toList());
        if (tools.isEmpty()) {
            return null;
        }
        if (tools.size() == 1) {
            return tools.get(0);
        }
        String query = capability + " " + (inputNode != null ? inputNode.toString() : "");
        List<String> docs = tools.stream()
                .map(t -> "name=" + t.getName() + ";desc=" + t.getDescription())
                .toList();
        List<Double> scores = llmClientUtil.rerank(query, docs);
        List<Resource> ranked = llmClientUtil.rerankResources(tools, scores, 1);
        return ranked.isEmpty() ? tools.get(0) : ranked.get(0);
    }

    private String buildToolArgs(JsonNode inputNode, Map<String, Object> state) throws Exception {
        return objectMapper.writeValueAsString(inputNode);
    }

    private JsonNode safeToNode(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String safeJson(ExecutionResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"execution serialization failed\"}";
        }
    }

    private void recordToolTrace(Resource tool, String inputJson, String outputJson, String status, String errorMessage, long latencyMs) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("tool_name", tool == null ? "unknown_tool" : tool.getName());
        trace.put("call_status", status == null ? "UNKNOWN" : status);
        trace.put("source_type", "TOOL");
        trace.put("normalized_input", safeToNode(inputJson) == null ? Map.of("raw", inputJson == null ? "" : inputJson) : safeToNode(inputJson));
        trace.put("normalized_output", safeToNode(outputJson) == null ? Map.of("raw", outputJson == null ? "" : outputJson) : safeToNode(outputJson));
        trace.put("error_message", errorMessage == null ? "" : errorMessage);
        trace.put("latency_ms", Math.max(0L, latencyMs));
        ToolCallingContextHolder.appendToolExecutionTrace(trace);
    }

    private String error(String errorCode, String message, String workflowName, String missingToolSlot, String missingCapability) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "error");
            out.put("errorCode", errorCode);
            out.put("workflowName", workflowName == null ? "" : workflowName);
            out.put("message", message == null ? "" : message);
            if (missingToolSlot != null) out.put("missingToolSlot", missingToolSlot);
            if (missingCapability != null) out.put("missingCapability", missingCapability);
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return String.format(
                    "{\"status\":\"error\", \"errorCode\":\"%s\", \"workflowName\":\"%s\", \"message\":\"%s\"}",
                    errorCode,
                    workflowName == null ? "" : workflowName,
                    message == null ? "" : message.replace("\"", "\\\"")
            );
        }
    }

    private String validateExecutableWorkflow(Resource workflow) {
        if (workflow == null) {
            return error("WORKFLOW_CONFIG_INVALID", "workflow 不能为空", "", null, null);
        }
        if (workflow.getName() == null || workflow.getName().isBlank()) {
            return error("WORKFLOW_CONFIG_INVALID", "workflowName 不能为空", "", null, null);
        }
        if (workflow.getToolSlots() == null || workflow.getToolSlots().isEmpty()) {
            return error("WORKFLOW_CONFIG_INVALID", "工作流未配置 toolSlots", workflow.getName(), null, null);
        }

        Set<String> slotNames = new HashSet<>();
        for (Resource.ToolSlotDto slot : workflow.getToolSlots()) {
            if (slot == null) {
                return error("WORKFLOW_CONFIG_INVALID", "toolSlots 不能包含空元素", workflow.getName(), null, null);
            }
            if (slot.getSlot() == null || slot.getSlot().isBlank()) {
                return error("WORKFLOW_CONFIG_INVALID", "toolSlots.slot 不能为空", workflow.getName(), null, null);
            }
            if (!slotNames.add(slot.getSlot())) {
                return error("WORKFLOW_CONFIG_INVALID", "toolSlots.slot 重复: " + slot.getSlot(), workflow.getName(), null, null);
            }
            if (slot.getCapability() == null || slot.getCapability().isBlank()) {
                return error("WORKFLOW_CONFIG_INVALID", "toolSlots.capability 不能为空", workflow.getName(), null, null);
            }
        }

        List<String> chain = workflow.getThoughtChain() == null ? Collections.emptyList() : workflow.getThoughtChain();
        if (!chain.isEmpty() && chain.size() != workflow.getToolSlots().size()) {
            return error("WORKFLOW_CONFIG_INVALID", "thoughtChain 长度必须与 toolSlots 一致", workflow.getName(), null, null);
        }

        if (workflow.getRequiredCapabilities() != null && !workflow.getRequiredCapabilities().isEmpty()) {
            Set<String> capInSlots = workflow.getToolSlots().stream()
                    .map(Resource.ToolSlotDto::getCapability)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .collect(Collectors.toSet());
            for (String c : workflow.getRequiredCapabilities()) {
                if (c == null || c.isBlank()) {
                    return error("WORKFLOW_CONFIG_INVALID", "requiredCapabilities 不能包含空值", workflow.getName(), null, null);
                }
                if (!capInSlots.contains(c.trim())) {
                    return error("WORKFLOW_CONFIG_INVALID", "requiredCapabilities 未在 toolSlots 中声明: " + c, workflow.getName(), null, null);
                }
            }
        }
        return null;
    }
}
