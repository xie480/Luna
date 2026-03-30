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
public class ExecutionLockTools extends BaseTool {

    private static final String LOCK_KEY_PREFIX = "luna:openclaw:lock:"; // 声明成员字段

    private static final String RELEASE_LOCK_LUA = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;

    private final StringRedisTemplate stringRedisTemplate; // 声明成员字段

    public ExecutionLockTools(ObjectMapper objectMapper, StringRedisTemplate stringRedisTemplate) { // 定义方法签名
        super(objectMapper); // 执行语句逻辑
        this.stringRedisTemplate = stringRedisTemplate; // 执行赋值操作
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_LOCK, status = LunaStateConstant.STATUS_LOCK) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "获取执行锁") // 声明注解
    public String acquireExecutionLock( // 定义方法签名
            @RequestParam("lockKey") String lockKey, // 声明注解
            @RequestParam("owner") String owner, // 声明注解
            @RequestParam(value = "ttlSec", required = false) Integer ttlSec // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            if (lockKey == null || lockKey.isBlank()) { // 进行条件判断
                return error("lockKey 不能为空"); // 返回处理结果
            } // 结束当前代码块
            if (owner == null || owner.isBlank()) { // 进行条件判断
                return error("owner 不能为空"); // 返回处理结果
            } // 结束当前代码块

            int ttl = (ttlSec == null || ttlSec <= 0) ? 60 : ttlSec; // 执行赋值操作
            String redisKey = LOCK_KEY_PREFIX + lockKey.trim(); // 执行赋值操作

            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, owner.trim(), java.time.Duration.ofSeconds(ttl)); // 执行赋值操作

            long expireAt = Instant.now().plusSeconds(ttl).toEpochMilli(); // 执行赋值操作

            if (Boolean.TRUE.equals(acquired)) { // 进行条件判断
                log.info("acquire_execution_lock 成功, lockKey={}, owner={}, ttlSec={}", lockKey, owner, ttl); // 执行赋值操作
                return success(Map.of( // 返回处理结果
                        "acquired", true, // 执行当前逻辑
                        "lockKey", lockKey, // 执行当前逻辑
                        "owner", owner, // 执行当前逻辑
                        "ttlSec", ttl, // 执行当前逻辑
                        "expireAt", expireAt // 执行当前逻辑
                )); // 执行语句逻辑
            } // 结束当前代码块

            String currentOwner = stringRedisTemplate.opsForValue().get(redisKey); // 执行赋值操作
            log.info("acquire_execution_lock 失败(已被占用), lockKey={}, owner={}, currentOwner={}", lockKey, owner, currentOwner); // 执行赋值操作
            return success(Map.of( // 返回处理结果
                    "acquired", false, // 执行当前逻辑
                    "lockKey", lockKey, // 执行当前逻辑
                    "owner", owner, // 执行当前逻辑
                    "currentOwner", currentOwner == null ? "" : currentOwner // 执行赋值操作
            )); // 执行语句逻辑
        } catch (Exception e) { // 开始新的代码块
            log.error("acquire_execution_lock 异常", e); // 执行语句逻辑
            return error("获取执行锁失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_LOCK, status = LunaStateConstant.STATUS_LOCK) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "释放执行锁") // 声明注解
    public String releaseExecutionLock( // 定义方法签名
            @RequestParam("lockKey") String lockKey, // 声明注解
            @RequestParam("owner") String owner // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            if (lockKey == null || lockKey.isBlank()) { // 进行条件判断
                return error("lockKey 不能为空"); // 返回处理结果
            } // 结束当前代码块
            if (owner == null || owner.isBlank()) { // 进行条件判断
                return error("owner 不能为空"); // 返回处理结果
            } // 结束当前代码块

            String redisKey = LOCK_KEY_PREFIX + lockKey.trim(); // 执行赋值操作

            DefaultRedisScript<Long> script = new DefaultRedisScript<>(); // 执行赋值操作
            script.setScriptText(RELEASE_LOCK_LUA); // 执行语句逻辑
            script.setResultType(Long.class); // 执行语句逻辑

            Long result = stringRedisTemplate.execute(script, Collections.singletonList(redisKey), owner.trim()); // 执行赋值操作
            boolean released = result != null && result > 0; // 执行赋值操作

            log.info("release_execution_lock 完成, lockKey={}, owner={}, released={}", lockKey, owner, released); // 执行赋值操作
            return success(Map.of( // 返回处理结果
                    "released", released, // 执行当前逻辑
                    "lockKey", lockKey, // 执行当前逻辑
                    "owner", owner // 执行当前逻辑
            )); // 执行语句逻辑
        } catch (Exception e) { // 开始新的代码块
            log.error("release_execution_lock 异常", e); // 执行语句逻辑
            return error("释放执行锁失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块
} // 结束当前代码块
