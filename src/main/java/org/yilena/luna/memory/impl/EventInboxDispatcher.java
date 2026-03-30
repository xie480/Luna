package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yilena.luna.memory.EventIngressService;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventInboxDispatcher {

    private final EventIngressService eventIngressService;

    @Scheduled(fixedDelayString = "${luna.event.dispatcher.delay-ms:5000}")
    public void dispatchPendingEvents() {
        try {
            eventIngressService.dispatchPendingEvents(50);
        } catch (Exception e) {
            log.warn("event dispatch failed: {}", e.getMessage());
        }
    }
}
