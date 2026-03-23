package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LunaStateConstant;

import java.util.Map;

/**
 * 执行锁工具：
 * - acquire_execution_lock
 * - release_execution_lock
 */
@Slf4j
@Component
public class ExecutionLockTools extends BaseTool {

    public ExecutionLockTools(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @LunaState(value = LunaStateConstant.VALUE_LOCK, status = LunaStateConstant.STATUS_LOCK)
    @LunaLogRecord(module = "tool", action = "acquire_execution_lock", content = "获取执行锁")
    public String acquireExecutionLock(
            @RequestParam("lockKey") String lockKey,
            @RequestParam("owner") String owner,
            @RequestParam(value = "ttlSec", required = false) Integer ttlSec
    ) {
        int ttl = ttlSec == null ? 60 : ttlSec;
        log.info("acquire_execution_lock, lockKey={}, owner={}, ttl={}", lockKey, owner, ttl);
        return success(Map.of("acquired", true, "lockKey", lockKey, "owner", owner, "ttlSec", ttl));
    }

    @LunaState(value = LunaStateConstant.VALUE_LOCK, status = LunaStateConstant.STATUS_LOCK)
    @LunaLogRecord(module = "tool", action = "release_execution_lock", content = "释放执行锁")
    public String releaseExecutionLock(
            @RequestParam("lockKey") String lockKey,
            @RequestParam("owner") String owner
    ) {
        log.info("release_execution_lock, lockKey={}, owner={}", lockKey, owner);
        return success(Map.of("released", true, "lockKey", lockKey, "owner", owner));
    }
}
