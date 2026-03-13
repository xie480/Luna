package org.yilena.luna.annotation.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.entity.LunaLog;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.service.LunaLogService;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class LunaLogAspect {

    private final LunaLogService lunaLogService;

    @Around("@annotation(lunaLogRecord)")
    public Object around(ProceedingJoinPoint point, LunaLogRecord lunaLogRecord) throws Throwable {
        long startTime = System.currentTimeMillis();
        Object result = null;
        Exception exception = null;

        try {
            result = point.proceed();
            return result;
        } catch (Exception e) {
            exception = e;
            throw e;
        } finally {
            long costTime = System.currentTimeMillis() - startTime;
            saveLog(point, lunaLogRecord, result, exception, costTime);
        }
    }

    private void saveLog(ProceedingJoinPoint point, LunaLogRecord annotation, Object result, Exception exception, long costTime) {
        try {
            MethodSignature signature = (MethodSignature) point.getSignature();
            String[] parameterNames = signature.getParameterNames();
            Object[] args = point.getArgs();

            Map<String, Object> requestData = new HashMap<>();
            if (parameterNames != null && args != null) {
                for (int i = 0; i < parameterNames.length && i < args.length; i++) {
                    // 简单过滤，避免序列化过大对象
                    if (args[i] != null) {
                        requestData.put(parameterNames[i], args[i]);
                    }
                }
            }

            LogType logType = annotation.type();
            if (exception != null) {
                logType = LogType.ERROR;
            }

            LunaLog logEntity = LunaLog.builder()
                    .logType(logType)
                    .module(annotation.module())
                    .action(annotation.action())
                    .requestData(requestData)
                    .responseData(result) // JacksonTypeHandler 会自动处理序列化
                    .costTime(costTime)
                    .createAt(LocalDateTime.now())
                    .traceId(UUID.randomUUID().toString())
                    .build();

            if (exception != null) {
                logEntity.setErrorMessage(exception.getMessage());
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                exception.printStackTrace(pw);
                logEntity.setErrorStack(sw.toString());
            }

            lunaLogService.save(logEntity);
        } catch (Exception e) {
            log.error("记录系统日志失败", e);
        }
    }
}
