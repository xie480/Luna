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
/**
 * LunaLogConsumer ??
 */
public class LunaLogConsumer implements RocketMQListener<LogMessage> {

    private final LunaLogService lunaLogService; // 声明成员字段

    @Override // 声明注解
    public void onMessage(LogMessage msg) { // 定义方法签名
        try { // 尝试执行核心逻辑
            LunaLog logEntity = LunaLog.builder() // 执行赋值操作
                    .logType(msg.getLogType()) // 执行当前逻辑
                    .module(msg.getModule()) // 执行当前逻辑
                    .action(msg.getAction()) // 执行当前逻辑
                    .content(msg.getContent()) // 执行当前逻辑
                    .requestData(msg.getRequestData()) // 执行当前逻辑
                    .responseData(msg.getResponseData()) // 执行当前逻辑
                    .errorMessage(msg.getErrorMessage()) // 执行当前逻辑
                    .errorStack(msg.getErrorStack()) // 执行当前逻辑
                    .costTime(msg.getCostTime()) // 执行当前逻辑
                    .traceId(msg.getTraceId()) // 执行当前逻辑
                    .createAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(msg.getCreateTime()), ZoneId.systemDefault())) // 执行当前逻辑
                    .build(); // 执行语句逻辑

            lunaLogService.save(logEntity); // 执行语句逻辑
        } catch (Exception e) { // 开始新的代码块
            log.error("日誌異步落庫失敗", e); // 执行语句逻辑
        } // 结束当前代码块
    } // 结束当前代码块
} // 结束当前代码块
