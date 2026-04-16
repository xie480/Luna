package org.yilena.luna.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Lazy;
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
/**
 * 工作流执行器，负责调度工具工作流的同步或异步执行，并沉淀步骤结果、状态流和工具调用轨迹。
 */
public class WorkflowExecutor {

    /**
     * RocketMQ 模板，用于派发异步工作流执行消息。
     */
    private final RocketMQTemplate rocketMQTemplate;
    /**
     * 工具执行网关，用于统一执行工作流步骤中命中的工具。
     */
    private final ToolExecutionGateway toolExecutionGateway;
    /**
     * MCP 服务，用于按能力搜索可执行工具。
     */
    private final McpService mcpService;
    /**
     * 大模型客户端工具，用于执行候选工具重排等能力。
     */
    private final LlmClientUtil llmClientUtil;
    /**
     * JSON 处理器，用于解析入参与构建响应。
     */
    private final ObjectMapper objectMapper;
    /**
     * 状态发布器，用于向前端推送工作流执行状态。
     */
    private final LunaStatusPublisher statusPublisher;

    public WorkflowExecutor(RocketMQTemplate rocketMQTemplate,
                            ToolExecutionGateway toolExecutionGateway,
                            @Lazy McpService mcpService,
                            LlmClientUtil llmClientUtil,
                            ObjectMapper objectMapper,
                            LunaStatusPublisher statusPublisher) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.toolExecutionGateway = toolExecutionGateway;
        this.mcpService = mcpService;
        this.llmClientUtil = llmClientUtil;
        this.objectMapper = objectMapper;
        this.statusPublisher = statusPublisher;
    }

    /**
     * 工作流统一执行入口，根据运行模式分发到异步消息派发或本地同步执行。
     */
    public String execute(Resource workflow, String argsJson) {
        log.info("Workflow dispatch start, workflowName={}, runMode={}", workflow.getName(), workflow.getRunMode());

        /**
         * 先校验工作流配置是否可执行，避免缺失关键槽位时进入执行链路。
         */
        String validateErr = validateExecutableWorkflow(workflow);
        if (validateErr != null) {
            return validateErr;
        }

        /**
         * 异步模式下只负责创建任务和投递消息，同步模式直接进入本地步骤执行循环。
         */
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

    // ... existing code ...

    /**
     * 同步执行工作流步骤循环，按槽位依次匹配工具、执行工具并汇总状态。
     * <p>
     * 该方法的主要流程包括：
     * 1. 解析输入参数和工作流配置（toolSlots、thoughtChain），建立共享状态容器
     * 2. 遍历每个工具槽位，按顺序执行：能力匹配 → 参数构建 → 工具调用 → 结果沉淀
     * 3. 必需槽位匹配失败或执行异常时中断后续步骤，可选槽位失败则跳过继续
     * 4. 每步执行结果写入共享state，供后续步骤消费依赖数据
     * 5. 记录工具执行轨迹（参数、输出、状态、耗时）用于审计和回放
     * 6. 汇总统计信息（成功数、失败数、总耗时、最终状态）
     * 7. 检查必需步骤是否有失败，如有则返回错误码和缺失能力信息
     * <p>
     * 工作流采用串行执行策略，步骤间通过state传递数据。
     * 支持必需/可选槽位区分，必需步骤失败会立即终止整个工作流。
     * 全程通过statusPublisher推送SSE事件，前端可实时看到执行进度。
     *
     * @param workflow 工作流资源对象，包含：
     *                 - name: 工作流名称
     *                 - runMode: 运行模式（SYNC/ASYNC）
     *                 - toolSlots: 工具槽位列表，定义每步的能力需求和约束
     *                 - thoughtChain: 思考链列表，描述每步的执行意图
     * @param argsJson 输入参数的JSON字符串，作为工作流的初始输入数据
     * @return String JSON格式的执行结果，包含：
     *         - workflowName: 工作流名称
     *         - runMode: 运行模式
     *         - stepResults: 各步骤执行结果列表（stepIndex、slot、toolName、status、costMs等）
     *         - successSteps: 成功步骤数
     *         - failedSteps: 失败步骤数
     *         - costMs: 总耗时（毫秒）
     *         - status: 最终状态（success/error）
     *         - state: 共享状态对象，包含所有步骤的输出数据
     *         - errorCode/message: 如果必需步骤失败，返回错误码和描述
     *         - missingToolSlot/missingCapability: 缺失的工具槽位和能力标识
     */
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
            /**
             * 先解析输入参数和工作流配置，建立步骤状态容器，作为整个执行过程的上下文。
             */
            JsonNode inputNode = parseArgs(argsJson);
            List<Resource.ToolSlotDto> slots = workflow.getToolSlots() != null ? workflow.getToolSlots() : Collections.emptyList();
            List<String> chain = workflow.getThoughtChain() != null ? workflow.getThoughtChain() : Collections.emptyList();

            if (slots.isEmpty()) {
                return error("WORKFLOW_CONFIG_INVALID", "工作流未配置 toolSlots，无法执行 step loop", workflow.getName(), null, null);
            }

            // 初始化共享状态容器，存储输入和各步骤输出
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("input", inputNode);

            int successCount = 0;
            int failCount = 0;

            /**
             * 按步骤顺序遍历工具槽位，逐步完成工具匹配、参数构造、执行和结果沉淀。
             */
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
                    /**
                     * 先按能力搜索并选择最合适的工具，必需槽位未命中时直接中断后续执行。
                     */
                    matchedTool = selectBestToolByCapability(slot.getCapability(), inputNode);
                    if (matchedTool == null) {
                        if (Boolean.TRUE.equals(slot.getRequired())) {
                            throw new IllegalStateException("未匹配到必需能力的工具: capability=" + slot.getCapability() + ", slot=" + slot.getSlot());
                        }
                        // 可选槽位未匹配到工具，跳过继续执行
                        step.put("status", "SKIPPED");
                        step.put("message", "可选槽位未匹配到工具，已跳过");
                        step.put("costMs", System.currentTimeMillis() - stepStart);
                        stepResults.add(step);
                        continue;
                    }

                    step.put("toolName", matchedTool.getName());
                    step.put("toolId", matchedTool.getId());

                    /**
                     * 基于当前输入和运行状态构建工具参数，再通过统一网关执行真实工具调用。
                     */
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

                    /**
                     * 保存步骤结果和工具轨迹，并将输出写回共享状态供后续步骤继续消费。
                     */
                    step.put("toolResult", toolResultNode != null ? toolResultNode : toolResult);
                    step.put("status", stepSuccess ? "SUCCESS" : "FAILED");
                    step.put("costMs", System.currentTimeMillis() - stepStart);
                    recordToolTrace(matchedTool, toolArgs, toolResult, stepSuccess ? "SUCCESS" : "FAILED", null, System.currentTimeMillis() - stepStart);

                    // 将工具输出写入共享状态，key为槽位名称
                    state.put(slot.getSlot(), toolResultNode != null ? toolResultNode : toolResult);
                    if (stepSuccess) {
                        successCount++;
                    } else {
                        failCount++;
                        // 必需步骤失败，终止工作流
                        if (Boolean.TRUE.equals(slot.getRequired())) {
                            stepResults.add(step);
                            break;
                        }
                    }
                } catch (Exception e) {
                    /**
                     * 单步执行异常时记录失败结果和调用轨迹，必需步骤失败则终止整个工作流。
                     */
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

            // 检查是否存在必需步骤失败
            boolean hasRequiredFailure = stepResults.stream().anyMatch(s ->
                    "FAILED".equals(s.get("status")) && Boolean.TRUE.equals(findRequiredBySlot(slots, String.valueOf(s.get("slot")))));

            /**
             * 所有步骤执行结束后汇总统计结果，区分必需步骤失败与整体成功两种终态。
             */
            result.put("stepResults", stepResults);
            result.put("successSteps", successCount);
            result.put("failedSteps", failCount);
            result.put("costMs", System.currentTimeMillis() - start);
            result.put("status", hasRequiredFailure ? "error" : "success");
            result.put("state", state);

            // 如果必需步骤失败，返回详细错误信息
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

    // ... existing code ...


    /**
     * 解析工作流入参，空入参时回退为空对象。
     */
    private JsonNode parseArgs(String argsJson) throws Exception {
        String content = (argsJson == null || argsJson.isBlank()) ? "{}" : argsJson;
        return objectMapper.readTree(content);
    }

    /**
     * 查询指定槽位是否被标记为必需步骤。
     */
    private Boolean findRequiredBySlot(List<Resource.ToolSlotDto> slots, String slotName) {
        return slots.stream()
                .filter(s -> Objects.equals(s.getSlot(), slotName))
                .map(Resource.ToolSlotDto::getRequired)
                .findFirst()
                .orElse(false);
    }

    /**
     * 判断工具返回结果是否表示成功，默认无状态字段时视为成功。
     */
    private boolean isToolSuccess(JsonNode node) {
        if (node == null || !node.isObject() || !node.has("status")) {
            return true;
        }
        String status = node.get("status").asText("");
        return "success".equalsIgnoreCase(status) || "ok".equalsIgnoreCase(status);
    }

    /**
     * 按能力搜索候选工具，并在存在多个候选时用重排结果选择最佳工具。
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
     * 构建工具调用参数，当前直接透传输入 JSON，保留后续按状态增强的扩展点。
     */
    private String buildToolArgs(JsonNode inputNode, Map<String, Object> state) throws Exception {
        return objectMapper.writeValueAsString(inputNode);
    }

    /**
     * 尝试将字符串解析为 JSON 节点，解析失败时返回 null。
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
     * 将执行结果安全序列化为 JSON，序列化失败时返回统一错误结构。
     */
    private String safeJson(ExecutionResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"execution serialization failed\"}";
        }
    }

    /**
     * 记录工具调用轨迹，供审计回放和问题排查链路复用。
     */
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

    /**
     * 构建工作流统一错误响应，兼容缺失工具槽位等结构化错误场景。
     */
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

    /**
     * 校验工作流是否具备可执行的基础配置，重点检查名称、槽位和必需能力声明。
     */
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
