package org.yilena.luna.service.impl;

import cn.hutool.core.lang.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.yilena.luna.adapter.LlmAdapter;
import org.yilena.luna.entity.ApprovalTask;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.exception.impl.NeedApprovalException;
import org.yilena.luna.executor.ReflectionToolExecutor;
import org.yilena.luna.service.ApprovalService;
import org.yilena.luna.sse.LunaStatusPublisher;
import org.yilena.luna.sse.SseSessionManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 審批服務實現類
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalServiceImpl implements ApprovalService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ReflectionToolExecutor reflectionToolExecutor;
    private final ObjectMapper objectMapper;
    private final SseSessionManager sseSessionManager;
    private final LunaStatusPublisher statusPublisher;
    private final LlmAdapter llmAdapter;

    private static final String REDIS_PREFIX = "luna:approval:";
    private static final long EXPIRE_MINUTES = 10;

    @Override
    public void createTaskAndInterrupt(String sessionId, Resource resource, String argsJson) {
        String taskId = UUID.fastUUID().toString();

        ApprovalTask task = ApprovalTask.builder()
                .taskId(taskId)
                .sessionId(sessionId)
                .skillName(resource.getName())
                .beanName(resource.getBeanName())
                .methodName(resource.getMethodName())
                .argsJson(argsJson)
                .createTime(System.currentTimeMillis())
                .build();

        // 1. 存入 Redis
        String key = REDIS_PREFIX + taskId;
        redisTemplate.opsForValue().set(key, task, EXPIRE_MINUTES, TimeUnit.MINUTES);

        log.info("已創建審批任務: {}, 等待用戶授權...", taskId);

        // 2. 推送 SSE 事件通知前端
        // 前端監聽 "APPROVAL_REQUEST" 事件
        sseSessionManager.send(LunaStatusPublisher.DEFAULT_CLIENT_ID, "APPROVAL_REQUEST", task);
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "PENDING_APPROVAL", "操作需要你的确认...");

        // 3. 拋出異常中斷當前執行流
        throw new NeedApprovalException(task);
    }

    @Override
    public String processApproval(String taskId, boolean approved) {
        String key = REDIS_PREFIX + taskId;
        ApprovalTask task = (ApprovalTask) redisTemplate.opsForValue().get(key);

        if (task == null) {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");
            return errorJson("審批任務已過期或不存在");
        }

        // 無論同意還是拒絕，都刪除 Redis 中的任務
        redisTemplate.delete(key);

        if (!approved) {
            log.info("用戶拒絕了任務: {}", taskId);
            String denied = errorJson("User denied the operation.");
            sseSessionManager.send(LunaStatusPublisher.DEFAULT_CLIENT_ID, "APPROVAL_RESULT", Map.of(
                    "taskId", taskId,
                    "approved", false,
                    "result", safeToJsonNode(denied)
            ));
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");
            return denied;
        }

        log.info("用戶同意了任務: {}, 開始恢復執行...", taskId);
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "THINKING", "Luna 正在处理审批后的操作...");

        // 調用 Executor 的內部方法直接執行（跳過敏感度檢查）
        String toolResult = reflectionToolExecutor.executeInternal(task.getBeanName(), task.getMethodName(), task.getArgsJson());

        // 補一輪模型輸出，避免前端只拿到工具原始 JSON 感覺“無大模型回覆”
        String finalReplyJson = buildLunaReplyAfterApproval(task, toolResult);

        sseSessionManager.send(LunaStatusPublisher.DEFAULT_CLIENT_ID, "APPROVAL_RESULT", Map.of(
                "taskId", taskId,
                "approved", true,
                "result", safeToJsonNode(finalReplyJson)
        ));
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");

        return finalReplyJson;
    }

    /**
     * 审批通过后，基于工具结果生成 Luna 风格答复（输出 emotion/reply JSON）
     */
    private String buildLunaReplyAfterApproval(ApprovalTask task, String toolResult) {
        try {
            String prompt = """
                    你是 Luna。现在有一段工具执行结果，请你用自然中文总结给用户。
                    要求：
                    1) 只输出单行 JSON
                    2) 必须包含字段：emotion, reply
                    3) emotion 取值示例：Smile/Soft/Solemn/Determined
                    4) reply 要直接可读，避免生硬的技术字段堆砌
                    
                    工具名：%s
                    工具参数：%s
                    工具结果：%s
                    """.formatted(
                    task.getSkillName() == null ? "" : task.getSkillName(),
                    task.getArgsJson() == null ? "" : task.getArgsJson(),
                    toolResult == null ? "" : toolResult
            );

            String modelOutput = llmAdapter.generate(prompt);
            JsonNode node = safeToJsonNode(modelOutput);

            if (node != null && node.hasNonNull("reply")) {
                String emotion = node.hasNonNull("emotion") ? node.get("emotion").asText("Smile") : "Smile";
                String reply = node.get("reply").asText();
                Map<String, Object> out = new HashMap<>();
                out.put("emotion", emotion);
                out.put("reply", reply);
                return objectMapper.writeValueAsString(out);
            }
        } catch (Exception e) {
            log.warn("审批后生成模型回复失败，回退工具结果直出: {}", e.getMessage());
        }

        // 兜底：至少保证前端能展示文本回复
        return wrapToolResultAsReply(toolResult);
    }

    private String wrapToolResultAsReply(String toolResult) {
        try {
            String replyText = "操作已完成。";
            JsonNode toolNode = safeToJsonNode(toolResult);
            if (toolNode != null) {
                if (toolNode.has("message")) {
                    replyText = toolNode.get("message").asText(replyText);
                } else if (toolNode.has("data")) {
                    replyText = "操作已完成，结果如下：" + toolNode.get("data").toString();
                } else {
                    replyText = "操作已完成，结果如下：" + toolNode.toString();
                }
            } else if (toolResult != null && !toolResult.isBlank()) {
                replyText = "操作已完成，结果如下：" + toolResult;
            }

            Map<String, Object> out = new HashMap<>();
            out.put("emotion", "Smile");
            out.put("reply", replyText);
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"emotion\":\"Smile\",\"reply\":\"操作已完成。\"}";
        }
    }

    private JsonNode safeToJsonNode(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String errorJson(String msg) {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("status", "error");
            map.put("message", msg);
            map.put("emotion", "Solemn");
            map.put("reply", "这次操作未能执行。原因：" + msg);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"status\":\"error\", \"message\":\"" + msg + "\",\"emotion\":\"Solemn\",\"reply\":\"这次操作未能执行。\"}";
        }
    }
}
