package org.yilena.luna.mq.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.constants.RocketMqConstant;
import org.yilena.luna.executor.WorkflowExecutor;
import org.yilena.luna.memory.MemoryHotLayerService;
import org.yilena.luna.mq.dto.SkillExecutionMessage;
import org.yilena.luna.sse.LunaStatusPublisher;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = RocketMqConstant.TOPIC_WORKFLOW_ASYNC, consumerGroup = RocketMqConstant.GROUP_WORKFLOW_ASYNC)
/**
 * 工作流异步执行消费者，负责消费异步任务消息并回写 Redis 状态、SSE 事件和内存痕迹。
 */
public class SkillExecutionConsumer implements RocketMQListener<SkillExecutionMessage> {

    /**
     * Redis 中存储异步任务状态的键前缀。
     */
    private static final String TASK_REDIS_KEY_PREFIX = "luna:workflow:task:";

    /**
     * 工作流执行器，用于真正执行异步任务主体。
     */
    private final WorkflowExecutor workflowExecutor;
    /**
     * Redis 模板，用于维护异步任务状态快照。
     */
    private final StringRedisTemplate stringRedisTemplate;
    /**
     * JSON 处理器，用于解析和构造异步结果负载。
     */
    private final ObjectMapper objectMapper;
    /**
     * 状态发布器，用于通知前端异步任务状态变化。
     */
    private final LunaStatusPublisher statusPublisher;
    /**
     * 热层记忆服务，用于清理异步任务挂起的工具调用痕迹。
     */
    private final MemoryHotLayerService memoryHotLayerService;

    @Override
    /**
     * 消费异步技能执行消息，负责执行工作流并维护任务结果回写链路。
     */
    public void onMessage(SkillExecutionMessage msg) {
        // 先做基础消息校验，避免无效任务进入执行链路。
        if (msg == null) {
            log.error("SkillAsync MQ：收到空消息，忽略处理");
            return;
        }

        /**
         * 消息通过基础判空后继续提取任务标识，缺失时直接放弃消费。
         */
        String taskId = msg.getTaskId();
        if (taskId == null || taskId.isBlank()) {
            log.error("SkillAsync MQ：消息缺少 taskId，msg={}", msg);
            return;
        }

        // 资源缺失时直接落失败态并通知前端。
        if (msg.getResource() == null) {
            log.error("SkillAsync MQ：消息缺少 resource，taskId={}", taskId);
            markTaskFailed(taskId, "resource is null", null, 0L);
            notifyAsyncResult(taskId, false, null, "RESOURCE_NULL", "resource is null", null, "FAILED", 0L);
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return;
        }

        /**
         * 任务进入运行阶段后准备执行参数和起始时间，用于后续状态回写与耗时统计。
         */
        String skillName = msg.getResource().getName();
        String argsJson = (msg.getArgsJson() == null || msg.getArgsJson().isBlank()) ? "{}" : msg.getArgsJson();
        long start = System.currentTimeMillis();

        // 任务进入运行态，便于前端轮询/回显。
        markTaskRunning(taskId, skillName);
        log.info("SkillAsync MQ：开始执行异步技能任务，taskId={}, skillName={}", taskId, skillName);

        try {
            /**
             * 执行工作流主流程，结果为错误状态时按业务失败处理，成功时回写完成态。
             */
            // 执行技能主流程并统计耗时。
            String result = workflowExecutor.executeLoop(msg.getResource(), argsJson);
            long costMs = System.currentTimeMillis() - start;

            // 结果体声明失败时，按业务失败路径处理。
            boolean failedByResult = isErrorResult(result);
            if (failedByResult) {
                String errMsg = extractErrorMessage(result);
                String errCode = extractErrorCode(result);
                log.warn("SkillAsync MQ：任务完成但结果为错误状态，taskId={}, skillName={}, costMs={}, err={}", taskId, skillName, costMs, errMsg);

                markTaskFailed(taskId, errMsg, skillName, costMs);
                notifyAsyncResult(taskId, false, result, errCode, errMsg, skillName, "FAILED", costMs);
                statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, "异步技能执行失败：" + skillName);
                return;
            }

            log.info("SkillAsync MQ：任务执行成功，taskId={}, skillName={}, costMs={}", taskId, skillName, costMs);
            // 成功态写回 Redis，并通过 SSE 通知前端完成。
            markTaskCompleted(taskId, result, skillName, costMs);
            notifyAsyncResult(taskId, true, result, "", null, skillName, "COMPLETED", costMs);
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, "异步技能执行完成：" + skillName);

        } catch (Exception e) {
            /**
             * 运行期异常统一按失败态回写 Redis 和 SSE，并继续抛出异常交由消息机制处理。
             */
            long costMs = System.currentTimeMillis() - start;
            String err = e.getMessage() != null ? e.getMessage() : e.toString();

            log.error("SkillAsync MQ：任务执行异常，taskId={}, skillName={}, costMs={}, err={}", taskId, skillName, costMs, err, e);

            // 异常态写回 Redis 并广播失败事件。
            markTaskFailed(taskId, err, skillName, costMs);
            notifyAsyncResult(taskId, false, null, "SKILL_EXECUTION_EXCEPTION", err, skillName, "FAILED", costMs);
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, "异步技能执行异常：" + skillName);

            throw new RuntimeException("Async skill execution failed, taskId=" + taskId + ", err=" + err, e);
        } finally {
            /**
             * 无论执行结果如何，都要清理挂起工具调用并恢复全局空闲状态。
             */
            memoryHotLayerService.clearPendingToolCallByTaskId(taskId);
            // 无论成功失败都将全局状态恢复到空闲态。
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
        }
    }

    /**
     * 将任务标记为运行中，并写入最新更新时间和技能名称。
     */
    private void markTaskRunning(String taskId, String skillName) {
        String key = TASK_REDIS_KEY_PREFIX + taskId;
        // 维护任务运行态快照，供轮询接口读取。
        Map<String, String> fields = new HashMap<>();
        fields.put("taskId", taskId);
        fields.put("status", "RUNNING");
        fields.put("skillName", safe(skillName));
        fields.put("updatedAt", LocalDateTime.now().toString());
        stringRedisTemplate.opsForHash().putAll(key, fields);
    }

    /**
     * 将任务标记为已完成，并保存最终结果与耗时。
     */
    private void markTaskCompleted(String taskId, String result, String skillName, long costMs) {
        String key = TASK_REDIS_KEY_PREFIX + taskId;
        // 维护任务完成态快照并记录结果。
        Map<String, String> fields = new HashMap<>();
        fields.put("taskId", taskId);
        fields.put("status", "COMPLETED");
        fields.put("skillName", safe(skillName));
        fields.put("result", safe(result));
        fields.put("costMs", String.valueOf(costMs));
        fields.put("updatedAt", LocalDateTime.now().toString());
        stringRedisTemplate.opsForHash().putAll(key, fields);
    }

    /**
     * 将任务标记为失败，并保存错误信息与耗时。
     */
    private void markTaskFailed(String taskId, String error, String skillName, long costMs) {
        String key = TASK_REDIS_KEY_PREFIX + taskId;
        // 维护任务失败态快照并记录错误信息。
        Map<String, String> fields = new HashMap<>();
        fields.put("taskId", taskId);
        fields.put("status", "FAILED");
        fields.put("skillName", safe(skillName));
        fields.put("error", safe(error));
        fields.put("costMs", String.valueOf(costMs));
        fields.put("updatedAt", LocalDateTime.now().toString());
        stringRedisTemplate.opsForHash().putAll(key, fields);
    }

    /**
     * 推送异步执行结果事件，同时兼容新的工作流事件名和旧的技能事件名。
     */
    private void notifyAsyncResult(String taskId, boolean success, String result, String errorCode, String error,
                                   String skillName, String status, long costMs) {
        try {
            // 组装统一事件载荷并通过 SSE 推送异步执行结果。
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", "WORKFLOW_ASYNC_RESULT");
            payload.put("taskId", taskId);
            payload.put("workflowName", skillName);
            payload.put("skillName", skillName);
            payload.put("planId", "");
            payload.put("phaseId", "");
            payload.put("nodeId", "");
            payload.put("status", status);
            payload.put("success", success);
            payload.put("message", success ? "异步技能执行完成" : "异步技能执行失败");
            payload.put("errorCode", errorCode == null ? "" : errorCode);
            payload.put("error", error);
            payload.put("result", result);
            payload.put("costMs", costMs);
            payload.put("retryCount", 0);
            payload.put("timestamp", System.currentTimeMillis());

            statusPublisher.publishEvent(LunaStatusPublisher.DEFAULT_CLIENT_ID, "WORKFLOW_ASYNC_RESULT", payload);
            /**
             * 淇濈暀鏃т簨浠跺悕锛岀敤浜庡吋瀹规湭鍚屾鍒版柊浜嬩欢鍗忚鐨勫墠绔垨鎺ュ叆鏂广€?
             */
            statusPublisher.publishEvent(LunaStatusPublisher.DEFAULT_CLIENT_ID, "SKILL_ASYNC_RESULT", payload);
            log.debug("SkillAsync SSE 推送完成，taskId={}, success={}", taskId, success);
        } catch (Exception e) {
            log.warn("SkillAsync SSE 推送失败，taskId={}, err={}", taskId, e.getMessage());
        }
    }

    /**
     * 判断执行结果是否为错误状态，约定 status=error 或 failed 视为失败。
     */
    private boolean isErrorResult(String result) {
        if (result == null || result.isBlank()) {
            return true;
        }
        try {
            // 约定 status=error/failed 视为业务失败。
            JsonNode node = objectMapper.readTree(result);
            if (node.has("status")) {
                String status = node.get("status").asText("");
                return "error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从结果 JSON 中提取错误信息，提取失败时返回兜底文案。
     */
    private String extractErrorMessage(String result) {
        if (result == null || result.isBlank()) {
            return "empty result";
        }
        try {
            JsonNode node = objectMapper.readTree(result);
            if (node.has("message")) {
                return node.get("message").asText("unknown error");
            }
            return "skill returned error status";
        } catch (Exception e) {
            return "skill returned error status";
        }
    }

    /**
     * 从结果 JSON 中提取错误码，缺失时返回默认错误码。
     */
    private String extractErrorCode(String result) {
        if (result == null || result.isBlank()) {
            return "UNKNOWN_ERROR";
        }
        try {
            JsonNode node = objectMapper.readTree(result);
            if (node.has("errorCode")) {
                return node.get("errorCode").asText("UNKNOWN_ERROR");
            }
            return "UNKNOWN_ERROR";
        } catch (Exception e) {
            return "UNKNOWN_ERROR";
        }
    }

    /**
     * 将可空文本转换为非空字符串，便于写入 Redis 字段。
     */
    private String safe(String text) {
        return text == null ? "" : text;
    }
}
