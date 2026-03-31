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

import java.util.*;
import java.util.stream.Collectors;

/**
 * 技能执行器（Skill Orchestrator）
 *
 * 设计目标：
 * 1) Skill 作为轻量编排器，按 toolSlots 做显式 step loop；
 * 2) Tool 作为原子执行单元，确保中间过程可观测、可追踪；
 * 3) 能力匹配采用“向量检索 + rerank”策略选最优工具；
 * 4) 在关键阶段向前端推送状态（SSE），提升可见性与可诊断性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillExecutor {

    private final RocketMQTemplate rocketMQTemplate;
    private final ToolExecutionGateway toolExecutionGateway;
    private final McpService mcpService;
    private final LlmClientUtil llmClientUtil;
    private final ObjectMapper objectMapper;
    private final LunaStatusPublisher statusPublisher;

    /**
     * Skill 执行入口：
     * - ASYNC：仅投递 MQ，不在当前线程执行
     * - SYNC：当前线程执行 step loop
     */
    public String execute(Resource skill, String argsJson) {
        log.info("Skill 调度开始，skillName={}, runMode={}", skill.getName(), skill.getRunMode());

        String validateErr = validateExecutableSkill(skill);
        if (validateErr != null) {
            return validateErr;
        }

        if (RunMode.ASYNC.equals(skill.getRunMode())) {
            String taskId = UUID.randomUUID().toString();
            log.info("Skill 异步任务已创建，taskId={}, skillName={}", taskId, skill.getName());

            statusPublisher.publish(
                    LunaStatusPublisher.DEFAULT_CLIENT_ID,
                    LunaStateConstant.STATUS_WORKING,
                    "Luna 正在后台执行技能：" + skill.getName()
            );

            SkillExecutionMessage msg = SkillExecutionMessage.builder()
                    .taskId(taskId)
                    .resource(skill)
                    .argsJson(argsJson)
                    .build();

            rocketMQTemplate.convertAndSend(RocketMqConstant.TOPIC_SKILL_ASYNC, msg);

            return String.format(
                    "{\"status\":\"pending\", \"taskId\":\"%s\", \"skillName\":\"%s\", \"message\":\"异步任务已提交，后台执行中\"}",
                    taskId,
                    skill.getName()
            );
        }

        return executeLoop(skill, argsJson);
    }

    /**
     * Step Loop 主逻辑（供 SYNC 直接调用，亦供 MQ Consumer 复用）
     */
    public String executeLoop(Resource skill, String argsJson) {
        long start = System.currentTimeMillis();

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();

        result.put("skillName", skill.getName());
        result.put("runMode", skill.getRunMode() != null ? skill.getRunMode().name() : "SYNC");

        statusPublisher.publish(
                LunaStatusPublisher.DEFAULT_CLIENT_ID,
                LunaStateConstant.STATUS_WORKING,
                "Luna 正在执行技能：" + skill.getName()
        );

        try {
            JsonNode inputNode = parseArgs(argsJson);

            List<Resource.ToolSlotDto> slots = skill.getToolSlots() != null ? skill.getToolSlots() : Collections.emptyList();
            List<String> chain = skill.getThoughtChain() != null ? skill.getThoughtChain() : Collections.emptyList();

            if (slots.isEmpty()) {
                log.warn("Skill 配置缺失 toolSlots，无法执行，skillName={}", skill.getName());
                return error("SKILL_CONFIG_INVALID", "技能未配置 toolSlots，无法执行 step loop", skill.getName(), null, null);
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
                        String.format("Luna 正在执行技能步骤 %d/%d：%s", i + 1, slots.size(), stepDesc)
                );

                Resource matchedTool = null;
                String toolArgs = "{}";
                try {
                    matchedTool = selectBestToolByCapability(slot.getCapability(), inputNode);

                    if (matchedTool == null) {
                        if (Boolean.TRUE.equals(slot.getRequired())) {
                            String missingMsg = "未匹配到必需能力的工具: capability=" + slot.getCapability() + ", slot=" + slot.getSlot();
                            throw new IllegalStateException(missingMsg);
                        } else {
                            step.put("status", "SKIPPED");
                            step.put("message", "可选槽位未匹配到工具，已跳过");
                            step.put("costMs", System.currentTimeMillis() - stepStart);
                            stepResults.add(step);
                            continue;
                        }
                    }

                    step.put("toolName", matchedTool.getName());
                    step.put("toolId", matchedTool.getId());

                    toolArgs = buildToolArgs(inputNode, state);
                    step.put("toolArgs", safeToNode(toolArgs));

                    String sessionId = AuthContextHolder.getSessionId();
                    ExecutionResult execResult = toolExecutionGateway.executeTool(
                            sessionId == null ? "skill-executor" : sessionId,
                            matchedTool,
                            toolArgs
                    );
                    String toolResult = execResult.getRawResult() == null
                            ? safeJson(execResult)
                            : execResult.getRawResult();
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
                    } else {
                        continue;
                    }
                }

                stepResults.add(step);
            }

            boolean hasRequiredFailure = stepResults.stream().anyMatch(s ->
                    "FAILED".equals(s.get("status")) &&
                            Boolean.TRUE.equals(findRequiredBySlot(slots, String.valueOf(s.get("slot"))))
            );

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

                String missingSlot = String.valueOf(firstRequiredFailure.getOrDefault("slot", ""));
                String missingCapability = String.valueOf(firstRequiredFailure.getOrDefault("capability", ""));

                String message = "技能执行失败：必需步骤缺少可用工具";
                result.put("errorCode", "SKILL_TOOL_MISSING");
                result.put("message", message);
                result.put("missingToolSlot", missingSlot);
                result.put("missingCapability", missingCapability);

                statusPublisher.publish(
                        LunaStatusPublisher.DEFAULT_CLIENT_ID,
                        LunaStateConstant.STATUS_THINKING,
                        "技能执行失败：" + skill.getName()
                );

                return objectMapper.writeValueAsString(result);
            } else {
                statusPublisher.publish(
                        LunaStatusPublisher.DEFAULT_CLIENT_ID,
                        LunaStateConstant.STATUS_THINKING,
                        "技能执行完成：" + skill.getName()
                );
            }

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("Skill 执行异常，skillName={}, err={}", skill.getName(), e.getMessage(), e);
            return error("SKILL_EXECUTION_EXCEPTION", "技能执行异常: " + e.getMessage(), skill.getName(), null, null);
        } finally {
            statusPublisher.publish(
                    LunaStatusPublisher.DEFAULT_CLIENT_ID,
                    LunaStateConstant.STATUS_IDLE,
                    LunaStateConstant.VALUE_IDLE
            );
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
        if (node == null || !node.isObject()) {
            return true;
        }
        if (!node.has("status")) {
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

    private void recordToolTrace(Resource tool,
                                 String inputJson,
                                 String outputJson,
                                 String status,
                                 String errorMessage,
                                 long latencyMs) {
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

    private String error(String errorCode, String message, String skillName, String missingToolSlot, String missingCapability) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "error");
            out.put("errorCode", errorCode);
            out.put("skillName", skillName == null ? "" : skillName);
            out.put("message", message == null ? "" : message);
            if (missingToolSlot != null) {
                out.put("missingToolSlot", missingToolSlot);
            }
            if (missingCapability != null) {
                out.put("missingCapability", missingCapability);
            }
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return String.format(
                    "{\"status\":\"error\", \"errorCode\":\"%s\", \"skillName\":\"%s\", \"message\":\"%s\"}",
                    errorCode,
                    skillName == null ? "" : skillName,
                    message == null ? "" : message.replace("\"", "\\\"")
            );
        }
    }

    private String validateExecutableSkill(Resource skill) {
        if (skill == null) {
            return error("SKILL_CONFIG_INVALID", "skill 不能为空", "", null, null);
        }
        if (skill.getName() == null || skill.getName().isBlank()) {
            return error("SKILL_CONFIG_INVALID", "skillName 不能为空", "", null, null);
        }
        if (skill.getToolSlots() == null || skill.getToolSlots().isEmpty()) {
            return error("SKILL_CONFIG_INVALID", "技能未配置 toolSlots", skill.getName(), null, null);
        }

        Set<String> slotNames = new HashSet<>();
        for (Resource.ToolSlotDto slot : skill.getToolSlots()) {
            if (slot == null) {
                return error("SKILL_CONFIG_INVALID", "toolSlots 不能包含空元素", skill.getName(), null, null);
            }
            if (slot.getSlot() == null || slot.getSlot().isBlank()) {
                return error("SKILL_CONFIG_INVALID", "toolSlots.slot 不能为空", skill.getName(), null, null);
            }
            if (!slotNames.add(slot.getSlot())) {
                return error("SKILL_CONFIG_INVALID", "toolSlots.slot 重复: " + slot.getSlot(), skill.getName(), null, null);
            }
            if (slot.getCapability() == null || slot.getCapability().isBlank()) {
                return error("SKILL_CONFIG_INVALID", "toolSlots.capability 不能为空", skill.getName(), null, null);
            }
        }

        String thoughtErr = ensureThoughtChainConsistency(skill);
        if (thoughtErr != null) {
            return thoughtErr;
        }

        if (skill.getRequiredCapabilities() != null && !skill.getRequiredCapabilities().isEmpty()) {
            Set<String> capInSlots = skill.getToolSlots().stream()
                    .map(Resource.ToolSlotDto::getCapability)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .collect(Collectors.toSet());

            for (String c : skill.getRequiredCapabilities()) {
                if (c == null || c.isBlank()) {
                    return error("SKILL_CONFIG_INVALID", "requiredCapabilities 不能包含空值", skill.getName(), null, null);
                }
                if (!capInSlots.contains(c.trim())) {
                    return error("SKILL_CONFIG_INVALID", "requiredCapabilities 未在 toolSlots 中声明: " + c, skill.getName(), null, null);
                }
            }
        }

        return null;
    }

    private String ensureThoughtChainConsistency(Resource skill) {
        List<String> chain = skill.getThoughtChain() == null ? Collections.emptyList() : skill.getThoughtChain();
        if (!chain.isEmpty() && chain.size() != skill.getToolSlots().size()) {
            return error("SKILL_CONFIG_INVALID", "thoughtChain 长度必须与 toolSlots 一致", skill.getName(), null, null);
        }
        return null;
    }
}
