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

/**
 * 日志切面，负责拦截带有日志注解的方法并异步发送结构化日志消息到 RocketMQ。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class LunaLogAspect {

    /**
     * RocketMQ 模板，用于异步发送日志消息。
     */
    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 用于在业务逻辑中覆盖默认的返回值日志记录。
     */
    public static final ThreadLocal<Object> LOG_RESPONSE_OVERRIDE = new ThreadLocal<>();

    /**
     * MQ 临时降级窗口，单位为毫秒。
     */
    private static final long MQ_DEGRADE_WINDOW_MS = 30_000L;

    /**
     * 当前 MQ 降级截止时间戳。
     */
    private final AtomicLong mqDegradeUntilMs = new AtomicLong(0L);

    @Around("@annotation(lunaLogRecord)")
    public Object around(ProceedingJoinPoint point, LunaLogRecord lunaLogRecord) throws Throwable {
        long startTime = System.currentTimeMillis();
        Object result = null;
        Exception exception = null;

        try {
            /**
             * 先执行目标方法，记录正常返回结果或捕获异常，便于 finally 中统一落日志。
             */
            result = point.proceed();
            return result;
        } catch (Exception e) {
            exception = e;
            throw e;
        } finally {
            /**
             * 无论成功还是失败，都按统一结构组装日志并尝试异步发送到 MQ。
             */
            long costTime = System.currentTimeMillis() - startTime;
            sendLogToMq(point, lunaLogRecord, result, exception, costTime);
        }
    }

    /**
     * 组装结构化日志消息并异步发送到 MQ，必要时进入短期降级窗口。
     */
    private void sendLogToMq(
            ProceedingJoinPoint point,
            LunaLogRecord annotation,
            Object result,
            Exception exception,
            long costTime
    ) {
        try {
            /**
             * 先提取方法参数、响应数据和异常信息，构造可审计的日志负载。
             */
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
                logType = LogType.ERROR;
            }

            Object responseData = LOG_RESPONSE_OVERRIDE.get();
            if (responseData == null) {
                responseData = result;
            }

            String errorMessage = null;
            String errorStack = null;
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

            /**
             * 若当前处于降级窗口则直接跳过 MQ 发送，避免高频故障持续拖慢主流程。
             */
            long now = System.currentTimeMillis();
            if (now < mqDegradeUntilMs.get()) {
                log.warn("日志MQ处于降级窗口，跳过发送，topic={}, 剩余={}ms",
                        RocketMqConstant.TOPIC_LOG, (mqDegradeUntilMs.get() - now));
                return;
            }

            /**
             * 采用异步发送避免阻塞主线程，失败时根据异常类型决定是否开启降级窗口。
             */
            Message<LogMessage> message = MessageBuilder.withPayload(msg).build();
            rocketMQTemplate.asyncSend(RocketMqConstant.TOPIC_LOG, message, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
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
                log.error("发送日志MQ失败", e);
            }
        } catch (Exception e) {
            if (isMqTemporarilyUnavailable(e)) {
                openDegradeWindow();
                log.error("发送日志MQ失败：Broker暂不可用，已进入降级窗口，topic={}", RocketMqConstant.TOPIC_LOG);
            } else {
                log.error("发送日志MQ失败", e);
            }
        } finally {
            /**
             * 最后清理线程级响应覆盖，避免 ThreadLocal 污染到后续请求。
             */
            LOG_RESPONSE_OVERRIDE.remove();
        }
    }

    /**
     * 打开短期 MQ 降级窗口，避免连续故障时频繁尝试发送。
     */
    private void openDegradeWindow() {
        mqDegradeUntilMs.set(System.currentTimeMillis() + MQ_DEGRADE_WINDOW_MS);
    }

    /**
     * 判断当前异常是否属于 MQ 临时不可用场景。
     */
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

    /**
     * 从请求数据中安全提取字符串字段，缺失时返回空字符串。
     */
    private String tryGetAsString(Map<String, Object> data, String key) {
        if (data == null || key == null) {
            return "";
        }
        Object val = data.get(key);
        if (val == null) {
            return "";
        }
        return String.valueOf(val);
    }

    /**
     * 判断参数是否为不适合直接记录到日志中的框架对象。
     */
    private boolean isFilterObject(Object arg) {
        return arg instanceof ServletRequest
                || arg instanceof ServletResponse
                || arg instanceof MultipartFile
                || arg instanceof BindingResult;
    }
}
