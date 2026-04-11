package org.yilena.luna.mq.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.yilena.luna.constants.RocketMqConstant;
import org.yilena.luna.entity.LunaLog;
import org.yilena.luna.mq.dto.LogMessage;
import org.yilena.luna.service.LunaLogService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 日志消费者，负责将 MQ 中的日志消息异步转换为数据库日志实体并落库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = RocketMqConstant.TOPIC_LOG, consumerGroup = RocketMqConstant.GROUP_LOG)
public class LunaLogConsumer implements RocketMQListener<LogMessage> {

    /**
     * 日志服务，用于执行异步日志持久化。
     */
    private final LunaLogService lunaLogService;

    @Override
    public void onMessage(LogMessage msg) {
        try {
            /**
             * 将 MQ 日志消息映射为数据库实体，统一补齐时间和结构化字段。
             */
            LunaLog logEntity = LunaLog.builder()
                    .logType(msg.getLogType())
                    .module(msg.getModule())
                    .action(msg.getAction())
                    .content(msg.getContent())
                    .requestData(msg.getRequestData())
                    .responseData(msg.getResponseData())
                    .errorMessage(msg.getErrorMessage())
                    .errorStack(msg.getErrorStack())
                    .costTime(msg.getCostTime())
                    .traceId(msg.getTraceId())
                    .createAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(msg.getCreateTime()), ZoneId.systemDefault()))
                    .build();

            /**
             * 异步写入日志表，将对话链路中的日志落库供后续审计和排障使用。
             */
            lunaLogService.save(logEntity);
        } catch (Exception e) {
            log.error("日志异步落库失败", e);
        }
    }
}
