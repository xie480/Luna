package org.yilena.luna.memory.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.yilena.luna.constants.RedisKeyConstant;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.MemoryHotLayerService;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultMemoryHotLayerService implements MemoryHotLayerService {

    private static final Duration SESSION_CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration WORKING_CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration COMPILED_CONTEXT_TTL = Duration.ofMinutes(3);
    private static final Duration EVENT_DEDUPE_TTL = Duration.ofSeconds(30);
    private static final Duration PENDING_TOOL_CALL_TTL = Duration.ofHours(2);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, Object> getSessionCache(String sessionId) {
        return readJsonMap(format(RedisKeyConstant.MEMORY_SESSION_CACHE_KEY, sessionId));
    }

    @Override
    public void putSessionCache(String sessionId, Map<String, Object> payload) {
        writeJson(format(RedisKeyConstant.MEMORY_SESSION_CACHE_KEY, sessionId), payload, SESSION_CACHE_TTL);
    }

    @Override
    public Map<String, Object> getWorkingMemoryCache(String sessionId) {
        return readJsonMap(format(RedisKeyConstant.MEMORY_WORKING_CACHE_KEY, sessionId));
    }

    @Override
    public void putWorkingMemoryCache(String sessionId, Map<String, Object> payload) {
        writeJson(format(RedisKeyConstant.MEMORY_WORKING_CACHE_KEY, sessionId), payload, WORKING_CACHE_TTL);
    }

    @Override
    public StructuredContextPackage getCompiledContextCache(String sessionId,
                                                            String userInput,
                                                            TaskRuntimeState taskState,
                                                            RelationalRuntimeState relationalState) {
        String key = format(
                RedisKeyConstant.MEMORY_COMPILED_CONTEXT_KEY,
                sessionId,
                fingerprint(userInput, taskState == null ? null : taskState.name(), relationalState == null ? null : relationalState.name())
        );
        Map<String, Object> map = readJsonMap(key);
        if (map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.convertValue(map, StructuredContextPackage.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void putCompiledContextCache(String sessionId,
                                        String userInput,
                                        TaskRuntimeState taskState,
                                        RelationalRuntimeState relationalState,
                                        StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return;
        }
        String key = format(
                RedisKeyConstant.MEMORY_COMPILED_CONTEXT_KEY,
                sessionId,
                fingerprint(userInput, taskState == null ? null : taskState.name(), relationalState == null ? null : relationalState.name())
        );
        writeJson(key, contextPackage, COMPILED_CONTEXT_TTL);
    }

    @Override
    public boolean tryDedupeEvent(String sessionId, String eventType, String payloadJson) {
        String key = format(
                RedisKeyConstant.MEMORY_EVENT_DEDUPE_KEY,
                sessionId,
                safe(eventType),
                fingerprint(payloadJson)
        );
        try {
            Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", EVENT_DEDUPE_TTL);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public void putPendingToolCall(String sessionId, String taskId, Map<String, Object> payload) {
        if (isBlank(sessionId) || isBlank(taskId)) {
            return;
        }
        Map<String, Object> safePayload = payload == null ? Collections.emptyMap() : payload;
        String taskKey = format(RedisKeyConstant.MEMORY_PENDING_TOOL_CALL_KEY, sessionId, taskId);
        String latestKey = format(RedisKeyConstant.MEMORY_PENDING_TOOL_CALL_LATEST_KEY, sessionId);
        String taskIndexKey = format(RedisKeyConstant.MEMORY_PENDING_TOOL_CALL_TASK_INDEX_KEY, taskId);

        writeJson(taskKey, safePayload, PENDING_TOOL_CALL_TTL);
        writeJson(latestKey, safePayload, PENDING_TOOL_CALL_TTL);
        try {
            stringRedisTemplate.opsForValue().set(taskIndexKey, sessionId, PENDING_TOOL_CALL_TTL);
        } catch (Exception e) {
            log.debug("set task index failed: {}", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getLatestPendingToolCall(String sessionId) {
        return readJsonMap(format(RedisKeyConstant.MEMORY_PENDING_TOOL_CALL_LATEST_KEY, sessionId));
    }

    @Override
    public void clearPendingToolCall(String sessionId, String taskId) {
        if (isBlank(sessionId)) {
            return;
        }
        try {
            if (!isBlank(taskId)) {
                stringRedisTemplate.delete(format(RedisKeyConstant.MEMORY_PENDING_TOOL_CALL_KEY, sessionId, taskId));
                stringRedisTemplate.delete(format(RedisKeyConstant.MEMORY_PENDING_TOOL_CALL_TASK_INDEX_KEY, taskId));
            }
            stringRedisTemplate.delete(format(RedisKeyConstant.MEMORY_PENDING_TOOL_CALL_LATEST_KEY, sessionId));
        } catch (Exception e) {
            log.debug("clear pending tool call failed: {}", e.getMessage());
        }
    }

    @Override
    public void clearPendingToolCallByTaskId(String taskId) {
        if (isBlank(taskId)) {
            return;
        }
        String taskIndexKey = format(RedisKeyConstant.MEMORY_PENDING_TOOL_CALL_TASK_INDEX_KEY, taskId);
        try {
            String sessionId = stringRedisTemplate.opsForValue().get(taskIndexKey);
            if (!isBlank(sessionId)) {
                clearPendingToolCall(sessionId, taskId);
            } else {
                stringRedisTemplate.delete(taskIndexKey);
            }
        } catch (Exception e) {
            log.debug("clear pending by task id failed: {}", e.getMessage());
        }
    }

    private Map<String, Object> readJsonMap(String key) {
        if (isBlank(key)) {
            return Collections.emptyMap();
        }
        try {
            String raw = stringRedisTemplate.opsForValue().get(key);
            if (isBlank(raw)) {
                return Collections.emptyMap();
            }
            return objectMapper.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private void writeJson(String key, Object value, Duration ttl) {
        if (isBlank(key) || value == null) {
            return;
        }
        try {
            String raw = objectMapper.writeValueAsString(value);
            if (ttl == null || ttl.isNegative() || ttl.isZero()) {
                stringRedisTemplate.opsForValue().set(key, raw);
            } else {
                stringRedisTemplate.opsForValue().set(key, raw, ttl.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            log.debug("write redis json failed, key={}, err={}", key, e.getMessage());
        }
    }

    private String format(String template, String... args) {
        if (template == null) {
            return "";
        }
        String[] safe = new String[args == null ? 0 : args.length];
        for (int i = 0; i < safe.length; i++) {
            safe[i] = this.safe(args[i]);
        }
        return String.format(template, (Object[]) safe);
    }

    private String fingerprint(String... inputs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String input : inputs) {
                digest.update(safe(input).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '|');
            }
            byte[] hash = digest.digest();
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit((b & 0xF), 16));
            }
            return builder.toString();
        } catch (Exception e) {
            return String.valueOf(System.nanoTime());
        }
    }

    private String safe(String text) {
        return text == null ? "" : text.trim();
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
