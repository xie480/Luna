package org.yilena.luna.annotation.aspect;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.messaging.MessagingException;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.constants.RocketMqConstant;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.exception.impl.NeedApprovalException;
import org.yilena.luna.mq.dto.LogMessage;
import org.yilena.luna.utils.AuthContextHolder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class LunaLogAspect {

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 用于在业务逻辑中覆盖默认的返回值日志记录
     */
    public static final ThreadLocal<Object> LOG_RESPONSE_OVERRIDE = new ThreadLocal<>();

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
            sendLogToMq(point, lunaLogRecord, result, exception, costTime);
        }
    }

    private void sendLogToMq(ProceedingJoinPoint point, LunaLogRecord annotation, Object result, Exception exception, long costTime) {
        try {
            MethodSignature signature = (MethodSignature) point.getSignature();
            String[] parameterNames = signature.getParameterNames();
            Object[] args = point.getArgs();

            Map<String, Object> requestData = new HashMap<>();
            if (parameterNames != null && args != null) {
                for (int i = 0; i < parameterNames.length && i < args.length; i++) {
                    Object arg = args[i];
                    if (arg != null && !isFilterObject(arg)) {
                        requestData.put(parameterNames[i], arg);
                    }
                }
            }

            boolean approvalInterrupt = exception instanceof NeedApprovalException;

            LogType logType = annotation.type();
            if (exception != null && !approvalInterrupt) {
                // 非审批中断异常才记为 ERROR
                logType = LogType.ERROR;
            }

            Object responseData = LOG_RESPONSE_OVERRIDE.get();
            if (responseData != null) {
                LOG_RESPONSE_OVERRIDE.remove();
            } else {
                responseData = result;
            }

            String errorMessage = null;
            String errorStack = null;

            // 审批中断属于正常业务流，不记录错误堆栈
            if (exception != null && !approvalInterrupt) {
                errorMessage = exception.getMessage();
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                exception.printStackTrace(pw);
                errorStack = sw.toString();
            }

            String traceId = UUID.randomUUID().toString();
            String sessionId = AuthContextHolder.getSessionId();
            String planId = tryGetAsString(requestData, "planId");
            String phaseId = tryGetAsString(requestData, "phaseId");
            String nodeId = tryGetAsString(requestData, "nodeId");

            // 構建 DTO 發送 MQ
            LogMessage msg = LogMessage.builder()
                    .logType(logType)
                    .module(annotation.module())
                    .action(annotation.action())
                    .content(annotation.content())
                    .requestData(requestData)
                    .responseData(responseData)
                    .errorMessage(errorMessage)
                    .errorStack(errorStack)
                    .costTime(costTime)
                    .traceId(traceId)
                    .createTime(System.currentTimeMillis())
                    .sessionId(sessionId)
                    .planId(planId)
                    .phaseId(phaseId)
                    .nodeId(nodeId)
                    .build();

            rocketMQTemplate.convertAndSend(RocketMqConstant.TOPIC_LOG, msg);

        } catch (MessagingException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("No route info of this topic")) {
                log.error("發送日誌 MQ 失敗：Topic 路由不存在或未就緒，topic={}", RocketMqConstant.TOPIC_LOG);
            } else {
                log.error("發送日誌 MQ 失敗", e);
            }
            LOG_RESPONSE_OVERRIDE.remove();
        } catch (Exception e) {
            log.error("發送日誌 MQ 失敗", e);
            LOG_RESPONSE_OVERRIDE.remove();
        }
    }

    private String tryGetAsString(Map<String, Object> data, String key) {
        if (data == null || key == null) return "";
        Object val = data.get(key);
        if (val == null) return "";
        return String.valueOf(val);
    }

    private boolean isFilterObject(Object arg) {
        return arg instanceof ServletRequest ||
               arg instanceof ServletResponse ||
               arg instanceof MultipartFile ||
               arg instanceof BindingResult;
    }
}
