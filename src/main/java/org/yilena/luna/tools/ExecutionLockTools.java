package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.enums.LogType;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 执行锁工具：
 * - acquire_execution_lock
 * - release_execution_lock
 *
 * 说明：
 * 使用 Redis 实现分布式互斥锁：
 * 1) acquire: SET key value NX EX ttl
 * 2) release: Lua 脚本校验 owner 后删除，防误删
 */
@Slf4j
@Component
/**
 * 执行锁工具类，负责通过 Redis 管理分布式执行锁，避免同一任务被重复并发执行。
 */
public class ExecutionLockTools extends BaseTool {

    /**
     * 执行锁在 Redis 中的统一键前缀，用于隔离业务锁空间。
     */
    private static final String LOCK_KEY_PREFIX = "luna:openclaw:lock:";

    /**
     * 释放锁的 Lua 脚本，确保只有锁持有者才能删除对应锁键。
     */
    private static final String RELEASE_LOCK_LUA = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;

    /**
     * Redis 模板，用于执行加锁、查锁和 Lua 解锁操作。
     */
    private final StringRedisTemplate stringRedisTemplate;

    public ExecutionLockTools(ObjectMapper objectMapper, StringRedisTemplate stringRedisTemplate) {
        super(objectMapper);
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @LunaState(value = LunaStateConstant.VALUE_LOCK, status = LunaStateConstant.STATUS_LOCK)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "获取执行锁")
    /**
     * 尝试获取执行锁，成功时返回过期时间，失败时返回当前持有者信息。
     */
    public String acquireExecutionLock(
            @RequestParam("lockKey") String lockKey,
            @RequestParam("owner") String owner,
            @RequestParam(value = "ttlSec", required = false) Integer ttlSec
    ) {
        try {
            /**
             * 先校验锁键和持有者，避免写入无意义的锁记录。
             */
            if (lockKey == null || lockKey.isBlank()) {
                return error("lockKey 不能为空");
            }
            if (owner == null || owner.isBlank()) {
                return error("owner 不能为空");
            }

            int ttl = (ttlSec == null || ttlSec <= 0) ? 60 : ttlSec;
            String redisKey = LOCK_KEY_PREFIX + lockKey.trim();

            /**
             * 使用 SET NX EX 语义尝试原子加锁，确保同一时刻只有一个持有者成功获取锁。
             */
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, owner.trim(), java.time.Duration.ofSeconds(ttl));

            long expireAt = Instant.now().plusSeconds(ttl).toEpochMilli();

            if (Boolean.TRUE.equals(acquired)) {
                log.info("acquire_execution_lock 成功, lockKey={}, owner={}, ttlSec={}", lockKey, owner, ttl);
                return success(Map.of(
                        "acquired", true,
                        "lockKey", lockKey,
                        "owner", owner,
                        "ttlSec", ttl,
                        "expireAt", expireAt
                ));
            }

            /**
             * 获取失败时补充当前持有者信息，便于上层决定等待、重试或放弃执行。
             */
            String currentOwner = stringRedisTemplate.opsForValue().get(redisKey);
            log.info("acquire_execution_lock 失败(已被占用), lockKey={}, owner={}, currentOwner={}", lockKey, owner, currentOwner);
            return success(Map.of(
                    "acquired", false,
                    "lockKey", lockKey,
                    "owner", owner,
                    "currentOwner", currentOwner == null ? "" : currentOwner
            ));
        } catch (Exception e) {
            log.error("acquire_execution_lock 异常", e);
            return error("获取执行锁失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_LOCK, status = LunaStateConstant.STATUS_LOCK)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "释放执行锁")
    /**
     * 释放执行锁，仅当请求方与锁持有者一致时才真正删除锁记录。
     */
    public String releaseExecutionLock(
            @RequestParam("lockKey") String lockKey,
            @RequestParam("owner") String owner
    ) {
        try {
            /**
             * 先校验解锁必要参数，避免误删其他执行主体持有的锁。
             */
            if (lockKey == null || lockKey.isBlank()) {
                return error("lockKey 不能为空");
            }
            if (owner == null || owner.isBlank()) {
                return error("owner 不能为空");
            }

            String redisKey = LOCK_KEY_PREFIX + lockKey.trim();

            /**
             * 通过 Lua 脚本校验 owner 后再删除锁，保证解锁操作具备原子性。
             */
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setScriptText(RELEASE_LOCK_LUA);
            script.setResultType(Long.class);

            Long result = stringRedisTemplate.execute(script, Collections.singletonList(redisKey), owner.trim());
            boolean released = result != null && result > 0;

            log.info("release_execution_lock 完成, lockKey={}, owner={}, released={}", lockKey, owner, released);
            return success(Map.of(
                    "released", released,
                    "lockKey", lockKey,
                    "owner", owner
            ));
        } catch (Exception e) {
            log.error("release_execution_lock 异常", e);
            return error("释放执行锁失败: " + e.getMessage());
        }
    }
}
