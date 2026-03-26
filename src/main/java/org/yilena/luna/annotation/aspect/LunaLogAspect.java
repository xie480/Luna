package org.yilena.luna.annotation.aspect;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;
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
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * MQ 临时降级窗口（毫秒）
     * 当检测到 broker 不可用时，在该窗口内跳过发送，避免请求线程反复打满错误日志。
     */
    private static final long MQ_DEGRADE_WINDOW_MS = 30_000L;

    /**
     * 降级截止时间戳（毫秒）
     */
    private final AtomicLong mqDegradeUntilMs = new AtomicLong(0L);

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
            if (responseData == null) {
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

            // 构建 DTO 发送 MQ
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

            // 若处于降级窗口，直接跳过发送，避免主流程被反复拖慢
            long now = System.currentTimeMillis();
            if (now < mqDegradeUntilMs.get()) {
                log.warn("日志MQ处于降级窗口，跳过发送，topic={}, 剩余={}ms",
                        RocketMqConstant.TOPIC_LOG, (mqDegradeUntilMs.get() - now));
                return;
            }

            // 异步发送，避免阻塞主业务线程
            Message<LogMessage> message = MessageBuilder.withPayload(msg).build();
            rocketMQTemplate.asyncSend(RocketMqConstant.TOPIC_LOG, message, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    // 高频路径不打印成功日志，避免噪音
                }

                @Override
                public void onException(Throwable e) {
                    if (isMqTemporarilyUnavailable(e)) {
                        openDegradeWindow();
                        log.error("发送日志MQ失败：Broker暂不可用，已进入降级窗口，topic={}", RocketMqConstant.TOPIC_LOG);
                    } else {
                        log.error("发送日志MQ失败（异步回调）", e);
                    }
                }
            });

        } catch (MessagingException e) {
            if (isMqTemporarilyUnavailable(e)) {
                openDegradeWindow();
                log.error("发送日志MQ失败：Broker暂不可用，已进入降级窗口，topic={}", RocketMqConstant.TOPIC_LOG);
            } else {
                log.error("发送日志 MQ 失败", e);
            }
        } catch (Exception e) {
            if (isMqTemporarilyUnavailable(e)) {
                openDegradeWindow();
                log.error("发送日志MQ失败：Broker暂不可用，已进入降级窗口，topic={}", RocketMqConstant.TOPIC_LOG);
            } else {
                log.error("发送日志 MQ 失败", e);
            }
        } finally {
            // 兜底清理，避免 ThreadLocal 泄漏到复用线程
            LOG_RESPONSE_OVERRIDE.remove();
        }
    }

    private void openDegradeWindow() {
        mqDegradeUntilMs.set(System.currentTimeMillis() + MQ_DEGRADE_WINDOW_MS);
    }

    private boolean isMqTemporarilyUnavailable(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("service not available")
                        || lower.contains("no route info of this topic")
                        || lower.contains("send [2] times, still failed")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
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
