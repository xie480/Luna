package org.yilena.luna.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.constants.RocketMqConstant;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.enums.RunMode;
import org.yilena.luna.mq.dto.SkillExecutionMessage;
import org.yilena.luna.service.McpService;
import org.yilena.luna.sse.LunaStatusPublisher;
import org.yilena.luna.utils.LlmClientUtil;

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
    private final ReflectionToolExecutor reflectionToolExecutor;
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

        if (RunMode.ASYNC.equals(skill.getRunMode())) {
            String taskId = UUID.randomUUID().toString();
            log.info("Skill 异步任务已创建，taskId={}, skillName={}", taskId, skill.getName());

            // 向前端提示：异步技能已进入后台执行
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
                    "{\"status\":\"pending\", \"taskId\":\"%s\", \"message\":\"异步任务已提交，后台执行中\"}",
                    taskId
            );
        }

        // 同步技能：当前线程直接执行 loop
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

        // 向前端提示：技能开始执行
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
                statusPublisher.publish(
                        LunaStatusPublisher.DEFAULT_CLIENT_ID,
                        LunaStateConstant.STATUS_IDLE,
                        LunaStateConstant.VALUE_IDLE
                );
                return error("技能未配置 toolSlots，无法执行 step loop");
            }

            // 编排态上下文（可用于后续 step 参数映射）
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

                log.info(
                        "Skill 步骤开始，skillName={}, step={}, slot={}, capability={}, required={}",
                        skill.getName(), i + 1, slot.getSlot(), slot.getCapability(), slot.getRequired()
                );

                // 向前端提示：当前进入某个步骤
                statusPublisher.publish(
                        LunaStatusPublisher.DEFAULT_CLIENT_ID,
                        LunaStateConstant.STATUS_THINKING,
                        String.format("Luna 正在执行技能步骤 %d/%d：%s", i + 1, slots.size(), stepDesc)
                );

                try {
                    Resource matchedTool = selectBestToolByCapability(slot.getCapability(), inputNode);

                    if (matchedTool == null) {
                        if (Boolean.TRUE.equals(slot.getRequired())) {
                            throw new IllegalStateException("未匹配到必需能力的工具: " + slot.getCapability());
                        } else {
                            step.put("status", "SKIPPED");
                            step.put("message", "可选槽位未匹配到工具，已跳过");
                            step.put("costMs", System.currentTimeMillis() - stepStart);
                            stepResults.add(step);

                            log.info(
                                    "Skill 步骤跳过（可选槽位无工具），skillName={}, step={}, slot={}",
                                    skill.getName(), i + 1, slot.getSlot()
                            );
                            continue;
                        }
                    }

                    step.put("toolName", matchedTool.getName());
                    step.put("toolId", matchedTool.getId());

                    String toolArgs = buildToolArgs(inputNode, state);
                    step.put("toolArgs", safeToNode(toolArgs));

                    log.info(
                            "Skill 步骤调用工具，skillName={}, step={}, toolName={}, toolId={}",
                            skill.getName(), i + 1, matchedTool.getName(), matchedTool.getId()
                    );

                    String toolResult = reflectionToolExecutor.execute(matchedTool, toolArgs);
                    JsonNode toolResultNode = safeToNode(toolResult);

                    boolean stepSuccess = isToolSuccess(toolResultNode);
                    step.put("toolResult", toolResultNode != null ? toolResultNode : toolResult);
                    step.put("status", stepSuccess ? "SUCCESS" : "FAILED");
                    step.put("costMs", System.currentTimeMillis() - stepStart);

                    // 写入 state，便于后续步骤消费
                    state.put(slot.getSlot(), toolResultNode != null ? toolResultNode : toolResult);

                    if (stepSuccess) {
                        successCount++;
                        log.info(
                                "Skill 步骤成功，skillName={}, step={}, slot={}, costMs={}",
                                skill.getName(), i + 1, slot.getSlot(), step.get("costMs")
                        );
                    } else {
                        failCount++;
                        log.warn(
                                "Skill 步骤失败（工具返回非 success），skillName={}, step={}, slot={}, required={}",
                                skill.getName(), i + 1, slot.getSlot(), slot.getRequired()
                        );

                        // 必需步骤失败，直接中止 loop
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

                    log.error(
                            "Skill 步骤异常，skillName={}, step={}, slot={}, required={}, err={}",
                            skill.getName(), i + 1, slot.getSlot(), slot.getRequired(), e.getMessage(), e
                    );

                    if (Boolean.TRUE.equals(slot.getRequired())) {
                        // 必需步骤异常，直接中止 loop
                        break;
                    } else {
                        // 可选步骤异常，继续后续步骤
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

            log.info(
                    "Skill 执行完成，skillName={}, status={}, successSteps={}, failedSteps={}, costMs={}",
                    skill.getName(),
                    result.get("status"),
                    successCount,
                    failCount,
                    result.get("costMs")
            );

            // 向前端推送最终状态
            if (hasRequiredFailure) {
                statusPublisher.publish(
                        LunaStatusPublisher.DEFAULT_CLIENT_ID,
                        LunaStateConstant.STATUS_THINKING,
                        "技能执行失败：" + skill.getName()
                );
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
            statusPublisher.publish(
                    LunaStatusPublisher.DEFAULT_CLIENT_ID,
                    LunaStateConstant.STATUS_THINKING,
                    "技能执行异常：" + skill.getName()
            );
            return error("技能执行异常: " + e.getMessage());
        } finally {
            // 无论成功失败，最后恢复前端状态为 IDLE
            statusPublisher.publish(
                    LunaStatusPublisher.DEFAULT_CLIENT_ID,
                    LunaStateConstant.STATUS_IDLE,
                    LunaStateConstant.VALUE_IDLE
            );
        }
    }

    /**
     * 解析 skill 入参 JSON，空参时按 {}
     */
    private JsonNode parseArgs(String argsJson) throws Exception {
        String content = (argsJson == null || argsJson.isBlank()) ? "{}" : argsJson;
        return objectMapper.readTree(content);
    }

    /**
     * 通过 slot 反查是否 required
     */
    private Boolean findRequiredBySlot(List<Resource.ToolSlotDto> slots, String slotName) {
        return slots.stream()
                .filter(s -> Objects.equals(s.getSlot(), slotName))
                .map(Resource.ToolSlotDto::getRequired)
                .findFirst()
                .orElse(false);
    }

    /**
     * 工具结果成功判定：
     * - 非 JSON：按成功处理（兼容旧工具）
     * - JSON 且含 status：status=success/ok 视为成功
     * - JSON 但无 status：按成功处理
     */
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

    /**
     * 根据 capability 选择最优 Tool：
     * 1) capability 文本检索候选资源
     * 2) 仅保留 TOOL 类型
     * 3) 使用 rerank 按“capability + 当前输入”重排，取 top1
     */
    private Resource selectBestToolByCapability(String capability, JsonNode inputNode) throws Exception {
        if (capability == null || capability.isBlank()) {
            log.warn("能力匹配跳过：capability 为空");
            return null;
        }

        List<Resource> candidates = mcpService.searchResources(capability);
        if (candidates == null || candidates.isEmpty()) {
            log.info("能力匹配无候选，capability={}", capability);
            return null;
        }

        List<Resource> tools = candidates.stream()
                .filter(r -> ResourceType.TOOL.equals(r.getType()))
                .collect(Collectors.toList());

        if (tools.isEmpty()) {
            log.info("能力匹配候选中无 TOOL，capability={}", capability);
            return null;
        }

        if (tools.size() == 1) {
            log.info("能力匹配仅单候选，直接命中，capability={}, tool={}", capability, tools.get(0).getName());
            return tools.get(0);
        }

        String query = capability + " " + (inputNode != null ? inputNode.toString() : "");
        List<String> docs = tools.stream()
                .map(t -> "name=" + t.getName() + ";desc=" + t.getDescription())
                .toList();

        List<Double> scores = llmClientUtil.rerank(query, docs);
        List<Resource> ranked = llmClientUtil.rerankResources(tools, scores, 1);

        Resource selected = ranked.isEmpty() ? tools.get(0) : ranked.get(0);
        log.info(
                "能力匹配完成，capability={}, candidates={}, selectedTool={}",
                capability, tools.size(), selected.getName()
        );
        return selected;
    }

    /**
     * 当前参数策略：透传 skill 入参给 tool。
     * 后续可基于 slot/capability 做字段映射与裁剪。
     */
    private String buildToolArgs(JsonNode inputNode, Map<String, Object> state) throws Exception {
        return objectMapper.writeValueAsString(inputNode);
    }

    /**
     * 尝试解析字符串为 JsonNode，失败返回 null（兼容纯文本结果）
     */
    private JsonNode safeToNode(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 统一错误 JSON
     */
    private String error(String message) {
        return "{\"status\":\"error\", \"message\":\"" + message + "\"}";
    }
}
