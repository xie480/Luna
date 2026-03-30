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

    private static final String TASK_REDIS_KEY_PREFIX = "luna:skill:task:"; // 声明成员字段

    private final SkillExecutor skillExecutor; // 声明成员字段
    private final StringRedisTemplate stringRedisTemplate; // 声明成员字段
    private final ObjectMapper objectMapper; // 声明成员字段
    private final LunaStatusPublisher statusPublisher; // 声明成员字段

    @Override // 声明注解
    public void onMessage(SkillExecutionMessage msg) { // 定义方法签名
        if (msg == null) { // 进行条件判断
            log.error("SkillAsync MQ：收到空消息，忽略处理"); // 执行语句逻辑
            return; // 返回处理结果
        } // 结束当前代码块

        String taskId = msg.getTaskId(); // 执行赋值操作
        if (taskId == null || taskId.isBlank()) { // 进行条件判断
            log.error("SkillAsync MQ：消息缺少 taskId，msg={}", msg); // 执行赋值操作
            return; // 返回处理结果
        } // 结束当前代码块

        if (msg.getResource() == null) { // 进行条件判断
            log.error("SkillAsync MQ：消息缺少 resource，taskId={}", taskId); // 执行赋值操作
            markTaskFailed(taskId, "resource is null", null, 0L); // 执行语句逻辑
            notifyAsyncResult(taskId, false, null, "RESOURCE_NULL", "resource is null", null, "FAILED", 0L); // 执行语句逻辑
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE); // 执行语句逻辑
            return; // 返回处理结果
        } // 结束当前代码块

        String skillName = msg.getResource().getName(); // 执行赋值操作
        String argsJson = (msg.getArgsJson() == null || msg.getArgsJson().isBlank()) ? "{}" : msg.getArgsJson(); // 执行赋值操作
        long start = System.currentTimeMillis(); // 执行赋值操作

        markTaskRunning(taskId, skillName); // 执行语句逻辑
        log.info("SkillAsync MQ：开始执行异步技能任务，taskId={}, skillName={}", taskId, skillName); // 执行赋值操作

        try { // 尝试执行核心逻辑
            String result = skillExecutor.executeLoop(msg.getResource(), argsJson); // 执行赋值操作
            long costMs = System.currentTimeMillis() - start; // 执行赋值操作

            boolean failedByResult = isErrorResult(result); // 执行赋值操作
            if (failedByResult) { // 进行条件判断
                String errMsg = extractErrorMessage(result); // 执行赋值操作
                String errCode = extractErrorCode(result); // 执行赋值操作
                log.warn("SkillAsync MQ：任务完成但结果为错误状态，taskId={}, skillName={}, costMs={}, err={}", taskId, skillName, costMs, errMsg); // 执行赋值操作

                markTaskFailed(taskId, errMsg, skillName, costMs); // 执行语句逻辑
                notifyAsyncResult(taskId, false, result, errCode, errMsg, skillName, "FAILED", costMs); // 执行语句逻辑
                statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, "异步技能执行失败：" + skillName); // 执行语句逻辑
                return; // 返回处理结果
            } // 结束当前代码块

            log.info("SkillAsync MQ：任务执行成功，taskId={}, skillName={}, costMs={}", taskId, skillName, costMs); // 执行赋值操作
            markTaskCompleted(taskId, result, skillName, costMs); // 执行语句逻辑
            notifyAsyncResult(taskId, true, result, "", null, skillName, "COMPLETED", costMs); // 执行语句逻辑
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, "异步技能执行完成：" + skillName); // 执行语句逻辑

        } catch (Exception e) { // 开始新的代码块
            long costMs = System.currentTimeMillis() - start; // 执行赋值操作
            String err = e.getMessage() != null ? e.getMessage() : e.toString(); // 执行赋值操作

            log.error("SkillAsync MQ：任务执行异常，taskId={}, skillName={}, costMs={}, err={}", taskId, skillName, costMs, err, e); // 执行赋值操作

            markTaskFailed(taskId, err, skillName, costMs); // 执行语句逻辑
            notifyAsyncResult(taskId, false, null, "SKILL_EXECUTION_EXCEPTION", err, skillName, "FAILED", costMs); // 执行语句逻辑
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, "异步技能执行异常：" + skillName); // 执行语句逻辑

            throw new RuntimeException("Async skill execution failed, taskId=" + taskId + ", err=" + err, e); // 抛出异常信息
        } finally { // 开始新的代码块
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE); // 执行语句逻辑
        } // 结束当前代码块
    } // 结束当前代码块

    private void markTaskRunning(String taskId, String skillName) { // 定义方法签名
        String key = TASK_REDIS_KEY_PREFIX + taskId; // 执行赋值操作
        Map<String, String> fields = new HashMap<>(); // 执行赋值操作
        fields.put("taskId", taskId); // 执行语句逻辑
        fields.put("status", "RUNNING"); // 执行语句逻辑
        fields.put("skillName", safe(skillName)); // 执行语句逻辑
        fields.put("updatedAt", LocalDateTime.now().toString()); // 执行语句逻辑
        stringRedisTemplate.opsForHash().putAll(key, fields); // 执行语句逻辑
    } // 结束当前代码块

    private void markTaskCompleted(String taskId, String result, String skillName, long costMs) { // 定义方法签名
        String key = TASK_REDIS_KEY_PREFIX + taskId; // 执行赋值操作
        Map<String, String> fields = new HashMap<>(); // 执行赋值操作
        fields.put("taskId", taskId); // 执行语句逻辑
        fields.put("status", "COMPLETED"); // 执行语句逻辑
        fields.put("skillName", safe(skillName)); // 执行语句逻辑
        fields.put("result", safe(result)); // 执行语句逻辑
        fields.put("costMs", String.valueOf(costMs)); // 执行语句逻辑
        fields.put("updatedAt", LocalDateTime.now().toString()); // 执行语句逻辑
        stringRedisTemplate.opsForHash().putAll(key, fields); // 执行语句逻辑
    } // 结束当前代码块

    private void markTaskFailed(String taskId, String error, String skillName, long costMs) { // 定义方法签名
        String key = TASK_REDIS_KEY_PREFIX + taskId; // 执行赋值操作
        Map<String, String> fields = new HashMap<>(); // 执行赋值操作
        fields.put("taskId", taskId); // 执行语句逻辑
        fields.put("status", "FAILED"); // 执行语句逻辑
        fields.put("skillName", safe(skillName)); // 执行语句逻辑
        fields.put("error", safe(error)); // 执行语句逻辑
        fields.put("costMs", String.valueOf(costMs)); // 执行语句逻辑
        fields.put("updatedAt", LocalDateTime.now().toString()); // 执行语句逻辑
        stringRedisTemplate.opsForHash().putAll(key, fields); // 执行语句逻辑
    } // 结束当前代码块

    private void notifyAsyncResult(String taskId, boolean success, String result, String errorCode, String error, // 定义方法签名
                                   String skillName, String status, long costMs) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            Map<String, Object> payload = new HashMap<>(); // 执行赋值操作
            payload.put("eventType", "SKILL_ASYNC_RESULT"); // 执行语句逻辑
            payload.put("taskId", taskId); // 执行语句逻辑
            payload.put("skillName", skillName); // 执行语句逻辑
            payload.put("planId", ""); // 执行语句逻辑
            payload.put("phaseId", ""); // 执行语句逻辑
            payload.put("nodeId", ""); // 执行语句逻辑
            payload.put("status", status); // 执行语句逻辑
            payload.put("success", success); // 执行语句逻辑
            payload.put("message", success ? "异步技能执行完成" : "异步技能执行失败"); // 执行语句逻辑
            payload.put("errorCode", errorCode == null ? "" : errorCode); // 执行赋值操作
            payload.put("error", error); // 执行语句逻辑
            payload.put("result", result); // 执行语句逻辑
            payload.put("costMs", costMs); // 执行语句逻辑
            payload.put("retryCount", 0); // 执行语句逻辑
            payload.put("timestamp", System.currentTimeMillis()); // 执行语句逻辑

            statusPublisher.publishEvent(LunaStatusPublisher.DEFAULT_CLIENT_ID, "SKILL_ASYNC_RESULT", payload); // 执行语句逻辑
            log.debug("SkillAsync SSE 推送完成，taskId={}, success={}", taskId, success); // 执行赋值操作
        } catch (Exception e) { // 开始新的代码块
            log.warn("SkillAsync SSE 推送失败，taskId={}, err={}", taskId, e.getMessage()); // 执行赋值操作
        } // 结束当前代码块
    } // 结束当前代码块

    private boolean isErrorResult(String result) { // 定义方法签名
        if (result == null || result.isBlank()) { // 进行条件判断
            return true; // 返回处理结果
        } // 结束当前代码块
        try { // 尝试执行核心逻辑
            JsonNode node = objectMapper.readTree(result); // 执行赋值操作
            if (node.has("status")) { // 进行条件判断
                String status = node.get("status").asText(""); // 执行赋值操作
                return "error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status); // 返回处理结果
            } // 结束当前代码块
            return false; // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return false; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private String extractErrorMessage(String result) { // 定义方法签名
        if (result == null || result.isBlank()) { // 进行条件判断
            return "empty result"; // 返回处理结果
        } // 结束当前代码块
        try { // 尝试执行核心逻辑
            JsonNode node = objectMapper.readTree(result); // 执行赋值操作
            if (node.has("message")) { // 进行条件判断
                return node.get("message").asText("unknown error"); // 返回处理结果
            } // 结束当前代码块
            return "skill returned error status"; // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return "skill returned error status"; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private String extractErrorCode(String result) { // 定义方法签名
        if (result == null || result.isBlank()) { // 进行条件判断
            return "UNKNOWN_ERROR"; // 返回处理结果
        } // 结束当前代码块
        try { // 尝试执行核心逻辑
            JsonNode node = objectMapper.readTree(result); // 执行赋值操作
            if (node.has("errorCode")) { // 进行条件判断
                return node.get("errorCode").asText("UNKNOWN_ERROR"); // 返回处理结果
            } // 结束当前代码块
            return "UNKNOWN_ERROR"; // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return "UNKNOWN_ERROR"; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private String safe(String text) { // 定义方法签名
        return text == null ? "" : text; // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
