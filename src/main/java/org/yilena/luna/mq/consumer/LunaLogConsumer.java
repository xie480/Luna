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

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = RocketMqConstant.TOPIC_LOG, consumerGroup = RocketMqConstant.GROUP_LOG)
public class LunaLogConsumer implements RocketMQListener<LogMessage> {

    private final LunaLogService lunaLogService;

    @Override
    public void onMessage(LogMessage msg) {
        try {
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

            lunaLogService.save(logEntity);
        } catch (Exception e) {
            log.error("日誌異步落庫失敗", e);
        }
    }
}
