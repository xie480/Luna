package org.yilena.luna.annotation.aspect;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;
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
                    Object arg = args[i];
                    // 过滤掉无法序列化的对象 (如 Request, Response, 文件等)
                    if (arg != null && !isFilterObject(arg)) {
                        requestData.put(parameterNames[i], arg);
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
                    .content(annotation.content()) // 修复：补充 content 字段赋值
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

    /**
     * 判断是否为需要过滤的对象
     * 过滤 ServletRequest, ServletResponse, MultipartFile, BindingResult 等无法直接序列化的对象
     */
    private boolean isFilterObject(Object arg) {
        return arg instanceof ServletRequest ||
               arg instanceof ServletResponse ||
               arg instanceof MultipartFile ||
               arg instanceof BindingResult;
    }
}
