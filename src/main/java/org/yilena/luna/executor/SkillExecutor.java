package org.yilena.luna.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;
import org.yilena.luna.constants.RocketMqConstant;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.enums.RunMode;
import org.yilena.luna.mq.dto.SkillExecutionMessage;
import org.yilena.luna.service.McpService;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 技能執行器（重構版）
 * 核心思路：
 * 1) Skill 作為編排器，按 toolSlots 逐步執行（step loop）
 * 2) Tool 作為原子執行單元，每步都可觀測、可重試（後續可擴展）
 * 3) 能力匹配使用向量檢索 + rerank
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillExecutor {

    private final RocketMQTemplate rocketMQTemplate;
    private final ReflectionToolExecutor reflectionToolExecutor;
    private final McpService mcpService;
    private final LlmClientUtil llmClientUtil;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    public String execute(Resource skill, String argsJson) {
        log.info("正在調度技能: {}", skill.getName());

        if (RunMode.ASYNC.equals(skill.getRunMode())) {
            String taskId = UUID.randomUUID().toString();
            log.info("提交異步任務至 MQ, TaskId: {}", taskId);

            SkillExecutionMessage msg = SkillExecutionMessage.builder()
                    .taskId(taskId)
                    .resource(skill)
                    .argsJson(argsJson)
                    .build();

            rocketMQTemplate.convertAndSend(RocketMqConstant.TOPIC_SKILL_ASYNC, msg);
            return String.format("{\"status\":\"pending\", \"taskId\":\"%s\", \"message\":\"異步任務已提交，後台執行中\"}", taskId);
        }

        // SYNC 直接執行 step loop
        return executeLoop(skill, argsJson);
    }

    /**
     * 供 MQ consumer 復用，保證 SYNC / ASYNC 執行語義一致
     */
    public String executeLoop(Resource skill, String argsJson) {
        long start = System.currentTimeMillis();

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();

        result.put("skillName", skill.getName());
        result.put("runMode", skill.getRunMode() != null ? skill.getRunMode().name() : "SYNC");

        try {
            JsonNode inputNode = parseArgs(argsJson);

            List<Resource.ToolSlotDto> slots = skill.getToolSlots() != null ? skill.getToolSlots() : Collections.emptyList();
            List<String> chain = skill.getThoughtChain() != null ? skill.getThoughtChain() : Collections.emptyList();

            if (slots.isEmpty()) {
                return error("技能未配置 toolSlots，無法執行 step loop");
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

                try {
                    Resource matchedTool = selectBestToolByCapability(slot.getCapability(), inputNode);
                    if (matchedTool == null) {
                        if (Boolean.TRUE.equals(slot.getRequired())) {
                            throw new IllegalStateException("未匹配到必需能力的工具: " + slot.getCapability());
                        } else {
                            step.put("status", "SKIPPED");
                            step.put("message", "可選槽位未匹配到工具，已跳過");
                            step.put("costMs", System.currentTimeMillis() - stepStart);
                            stepResults.add(step);
                            continue;
                        }
                    }

                    step.put("toolName", matchedTool.getName());
                    step.put("toolId", matchedTool.getId());

                    String toolArgs = buildToolArgs(inputNode, state);
                    step.put("toolArgs", safeToNode(toolArgs));

                    String toolResult = reflectionToolExecutor.execute(matchedTool, toolArgs);
                    JsonNode toolResultNode = safeToNode(toolResult);

                    step.put("toolResult", toolResultNode != null ? toolResultNode : toolResult);
                    step.put("status", isToolSuccess(toolResultNode) ? "SUCCESS" : "FAILED");
                    step.put("costMs", System.currentTimeMillis() - stepStart);

                    state.put(slot.getSlot(), toolResultNode != null ? toolResultNode : toolResult);

                    if (isToolSuccess(toolResultNode)) {
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

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("技能執行異常, skill={}", skill.getName(), e);
            return error("技能執行異常: " + e.getMessage());
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
            // 非 JSON 返回先按成功處理（兼容舊工具）
            return true;
        }
        if (!node.has("status")) {
            return true;
        }
        String status = node.get("status").asText("");
        return "success".equalsIgnoreCase(status) || "ok".equalsIgnoreCase(status);
    }

    /**
     * 能力匹配：
     * 1) 用 capability 查資源（向量檢索）
     * 2) 僅保留 TOOL
     * 3) 基於「當前輸入 + capability」做 rerank，取第一名
     */
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

    /**
     * 目前策略：直接把 Skill 入參透傳給 Tool。
     * 後續可按 slot/capability 做字段映射。
     */
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

    private String error(String message) {
        return "{\"status\":\"error\", \"message\":\"" + message + "\"}";
    }
}
