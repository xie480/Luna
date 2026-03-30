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
import org.yilena.luna.executor.SkillExecutor;
import org.yilena.luna.mq.dto.SkillExecutionMessage;
import org.yilena.luna.sse.LunaStatusPublisher;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = RocketMqConstant.TOPIC_SKILL_ASYNC, consumerGroup = RocketMqConstant.GROUP_SKILL_ASYNC)
/**
 * SkillExecutionConsumer ??
 */
public class SkillExecutionConsumer implements RocketMQListener<SkillExecutionMessage> {

    private static final String TASK_REDIS_KEY_PREFIX = "luna:skill:task:";

    private final SkillExecutor skillExecutor;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final LunaStatusPublisher statusPublisher;

    @Override
    public void onMessage(SkillExecutionMessage msg) {
        if (msg == null) {
            log.error("SkillAsync MQ：收到空消息，忽略处理");
            return;
        }

        String taskId = msg.getTaskId();
        if (taskId == null || taskId.isBlank()) {
            log.error("SkillAsync MQ：消息缺少 taskId，msg={}", msg);
            return;
        }

        if (msg.getResource() == null) {
            log.error("SkillAsync MQ：消息缺少 resource，taskId={}", taskId);
            markTaskFailed(taskId, "resource is null", null, 0L);
            notifyAsyncResult(taskId, false, null, "RESOURCE_NULL", "resource is null", null, "FAILED", 0L);
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return;
        }

        String skillName = msg.getResource().getName();
        String argsJson = (msg.getArgsJson() == null || msg.getArgsJson().isBlank()) ? "{}" : msg.getArgsJson();
        long start = System.currentTimeMillis();

        markTaskRunning(taskId, skillName);
        log.info("SkillAsync MQ：开始执行异步技能任务，taskId={}, skillName={}", taskId, skillName);

        try {
            String result = skillExecutor.executeLoop(msg.getResource(), argsJson);
            long costMs = System.currentTimeMillis() - start;

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
            markTaskCompleted(taskId, result, skillName, costMs);
            notifyAsyncResult(taskId, true, result, "", null, skillName, "COMPLETED", costMs);
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, "异步技能执行完成：" + skillName);

        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - start;
            String err = e.getMessage() != null ? e.getMessage() : e.toString();

            log.error("SkillAsync MQ：任务执行异常，taskId={}, skillName={}, costMs={}, err={}", taskId, skillName, costMs, err, e);

            markTaskFailed(taskId, err, skillName, costMs);
            notifyAsyncResult(taskId, false, null, "SKILL_EXECUTION_EXCEPTION", err, skillName, "FAILED", costMs);
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, "异步技能执行异常：" + skillName);

            throw new RuntimeException("Async skill execution failed, taskId=" + taskId + ", err=" + err, e);
        } finally {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
        }
    }

    private void markTaskRunning(String taskId, String skillName) {
        String key = TASK_REDIS_KEY_PREFIX + taskId;
        Map<String, String> fields = new HashMap<>();
        fields.put("taskId", taskId);
        fields.put("status", "RUNNING");
        fields.put("skillName", safe(skillName));
        fields.put("updatedAt", LocalDateTime.now().toString());
        stringRedisTemplate.opsForHash().putAll(key, fields);
    }

    private void markTaskCompleted(String taskId, String result, String skillName, long costMs) {
        String key = TASK_REDIS_KEY_PREFIX + taskId;
        Map<String, String> fields = new HashMap<>();
        fields.put("taskId", taskId);
        fields.put("status", "COMPLETED");
        fields.put("skillName", safe(skillName));
        fields.put("result", safe(result));
        fields.put("costMs", String.valueOf(costMs));
        fields.put("updatedAt", LocalDateTime.now().toString());
        stringRedisTemplate.opsForHash().putAll(key, fields);
    }

    private void markTaskFailed(String taskId, String error, String skillName, long costMs) {
        String key = TASK_REDIS_KEY_PREFIX + taskId;
        Map<String, String> fields = new HashMap<>();
        fields.put("taskId", taskId);
        fields.put("status", "FAILED");
        fields.put("skillName", safe(skillName));
        fields.put("error", safe(error));
        fields.put("costMs", String.valueOf(costMs));
        fields.put("updatedAt", LocalDateTime.now().toString());
        stringRedisTemplate.opsForHash().putAll(key, fields);
    }

    private void notifyAsyncResult(String taskId, boolean success, String result, String errorCode, String error,
                                   String skillName, String status, long costMs) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", "SKILL_ASYNC_RESULT");
            payload.put("taskId", taskId);
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

            statusPublisher.publishEvent(LunaStatusPublisher.DEFAULT_CLIENT_ID, "SKILL_ASYNC_RESULT", payload);
            log.debug("SkillAsync SSE 推送完成，taskId={}, success={}", taskId, success);
        } catch (Exception e) {
            log.warn("SkillAsync SSE 推送失败，taskId={}, err={}", taskId, e.getMessage());
        }
    }

    private boolean isErrorResult(String result) {
        if (result == null || result.isBlank()) {
            return true;
        }
        try {
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

    private String safe(String text) {
        return text == null ? "" : text;
    }
}
