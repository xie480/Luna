package org.yilena.luna.service;

import cn.hutool.core.lang.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.ApprovalTask;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.exception.impl.NeedApprovalException;
import org.yilena.luna.executor.ReflectionToolExecutor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ReflectionToolExecutor reflectionToolExecutor;
    private final ObjectMapper objectMapper;

    private static final String REDIS_PREFIX = "luna:approval:";
    private static final long EXPIRE_MINUTES = 10;

    /**
     * 創建審批任務並拋出中斷異常
     */
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

        // 存入 Redis
        String key = REDIS_PREFIX + taskId;
        redisTemplate.opsForValue().set(key, task, EXPIRE_MINUTES, TimeUnit.MINUTES);
        
        log.info("已創建審批任務: {}, 等待用戶授權...", taskId);
        
        // 拋出異常中斷當前執行流
        throw new NeedApprovalException(task);
    }

    /**
     * 處理用戶審批結果
     * @param taskId 任務ID
     * @param approved 是否同意
     * @return 執行結果 JSON
     */
    public String processApproval(String taskId, boolean approved) {
        String key = REDIS_PREFIX + taskId;
        ApprovalTask task = (ApprovalTask) redisTemplate.opsForValue().get(key);
        
        if (task == null) {
            return errorJson("審批任務已過期或不存在");
        }

        // 無論同意還是拒絕，都刪除 Redis 中的任務
        redisTemplate.delete(key);

        if (!approved) {
            log.info("用戶拒絕了任務: {}", taskId);
            return errorJson("User denied the operation.");
        }

        log.info("用戶同意了任務: {}, 開始恢復執行...", taskId);
        
        // 調用 Executor 的內部方法直接執行（跳過敏感度檢查）
        return reflectionToolExecutor.executeInternal(task.getBeanName(), task.getMethodName(), task.getArgsJson());
    }

    private String errorJson(String msg) {
        try {
            Map<String, String> map = new HashMap<>();
            map.put("status", "error");
            map.put("message", msg);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"status\":\"error\", \"message\":\"" + msg + "\"}";
        }
    }
}
