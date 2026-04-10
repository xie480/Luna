package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yilena.luna.memory.EventIngressService;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * 事件收件箱调度器，负责定时触发待处理事件消费，
 * 保障异步事件能够持续推进会话编排流程。
 */
public class EventInboxDispatcher {

    private final EventIngressService eventIngressService;

    @Scheduled(fixedDelayString = "${luna.event.dispatcher.delay-ms:5000}")
    /**
     * 定时分发事件收件箱中的待处理事件。
     */
    public void dispatchPendingEvents() {
        try {
            eventIngressService.dispatchPendingEvents(50);
        } catch (Exception e) {
            log.warn("event dispatch failed: {}", e.getMessage());
        }
    }
}
