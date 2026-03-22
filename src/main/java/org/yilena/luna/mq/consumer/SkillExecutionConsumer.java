package org.yilena.luna.mq.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.yilena.luna.constants.RocketMqConstant;
import org.yilena.luna.executor.SkillExecutor;
import org.yilena.luna.mq.dto.SkillExecutionMessage;
import org.yilena.luna.sse.LunaStatusPublisher;
import org.yilena.luna.sse.SseSessionManager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = RocketMqConstant.TOPIC_SKILL_ASYNC, consumerGroup = RocketMqConstant.GROUP_SKILL_ASYNC)
public class SkillExecutionConsumer implements RocketMQListener<SkillExecutionMessage> {

    private static final String TASK_REDIS_KEY_PREFIX = "luna:skill:task:";

    private final SkillExecutor skillExecutor;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final SseSessionManager sseSessionManager;

    @Override
    public void onMessage(SkillExecutionMessage msg) {
        if (msg == null) {
            log.error("MQ 消費: 收到空消息，忽略處理");
            return;
        }

        String taskId = msg.getTaskId();
        if (taskId == null || taskId.isBlank()) {
            log.error("MQ 消費: 消息缺少 taskId，忽略處理，msg={}", msg);
            return;
        }

        if (msg.getResource() == null) {
            log.error("MQ 消費: 消息缺少 resource，taskId={}", taskId);
            markTaskFailed(taskId, "resource is null", null, 0L);
            notifyAsyncResult(taskId, false, null, "resource is null");
            return;
        }

        String skillName = msg.getResource().getName();
        String argsJson = (msg.getArgsJson() == null || msg.getArgsJson().isBlank()) ? "{}" : msg.getArgsJson();

        long start = System.currentTimeMillis();
        markTaskRunning(taskId, skillName);

        log.info("MQ 消費: 開始執行異步技能任務, taskId={}, skill={}", taskId, skillName);

        try {
            String result = skillExecutor.executeLoop(msg.getResource(), argsJson);
            long costMs = System.currentTimeMillis() - start;

            boolean failedByResult = isErrorResult(result);
            if (failedByResult) {
                String errMsg = extractErrorMessage(result);
                log.warn("異步技能任務執行完成但返回錯誤狀態, taskId={}, costMs={}, err={}", taskId, costMs, errMsg);
                markTaskFailed(taskId, errMsg, skillName, costMs);
                notifyAsyncResult(taskId, false, result, errMsg);
                return;
            }

            log.info("異步技能任務執行成功, taskId={}, costMs={}", taskId, costMs);
            markTaskCompleted(taskId, result, skillName, costMs);
            notifyAsyncResult(taskId, true, result, null);

        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - start;
            String err = e.getMessage() != null ? e.getMessage() : e.toString();

            log.error("異步技能任務執行失敗, taskId={}, costMs={}, err={}", taskId, costMs, err, e);
            markTaskFailed(taskId, err, skillName, costMs);
            notifyAsyncResult(taskId, false, null, err);

            throw new RuntimeException("Async skill execution failed, taskId=" + taskId + ", err=" + err, e);
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

    private void notifyAsyncResult(String taskId, boolean success, String result, String error) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("taskId", taskId);
            payload.put("success", success);
            payload.put("result", result);
            payload.put("error", error);
            payload.put("timestamp", System.currentTimeMillis());

            sseSessionManager.send(LunaStatusPublisher.DEFAULT_CLIENT_ID, "SKILL_ASYNC_RESULT", payload);
        } catch (Exception e) {
            log.warn("推送異步技能結果 SSE 失敗, taskId={}, err={}", taskId, e.getMessage());
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

    private String safe(String text) {
        return text == null ? "" : text;
    }
}
