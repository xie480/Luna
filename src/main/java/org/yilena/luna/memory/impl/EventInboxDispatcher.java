package org.yilena.luna.memory.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.SessionOrchestratorService;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventInboxDispatcher {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SessionOrchestratorService sessionOrchestratorService;
    private final RuntimeAuditService runtimeAuditService;

    @Scheduled(fixedDelayString = "${luna.event.dispatcher.delay-ms:5000}")
    public void dispatchPendingEvents() {
        List<Map<String, Object>> events = fetchPendingEvents();
        for (Map<String, Object> event : events) {
            Long eventId = toLong(event.get("event_id"));
            String sessionId = str(event.get("session_id"));
            String eventType = str(event.get("event_type"));
            String payload = str(event.get("payload_json"));
            try {
                handleEvent(sessionId, eventType, payload);
                markProcessed(eventId);
            } catch (Exception e) {
                markFailed(eventId);
                log.warn("event dispatch failed, eventId={}, type={}, err={}", eventId, eventType, e.getMessage());
            }
        }
    }

    private List<Map<String, Object>> fetchPendingEvents() {
        try {
            return jdbcTemplate.queryForList(
                    "select event_id, session_id, event_type, payload_json " +
                            "from event_inbox where status = 'PENDING' order by created_at asc limit 50"
            );
        } catch (Exception ignore) {
            return List.of();
        }
    }

    private void handleEvent(String sessionId, String eventType, String payloadJson) throws Exception {
        if (eventType == null || eventType.isBlank()) {
            return;
        }
        JsonNode payload = parsePayload(payloadJson);
        switch (eventType) {
            case "USER_INPUT" -> {
                String text = payload.path("text").asText("");
                sessionOrchestratorService.onUserInput(sessionId, text);
                runtimeAuditService.persistDecisionRecord(
                        sessionId,
                        "EVENT_USER_INPUT",
                        "processed from event_inbox",
                        payload.toString()
                );
            }
            case "TOOL_RESULT" -> runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    "EVENT_TOOL_RESULT",
                    "tool callback received",
                    payload.toString()
            );
            case "APPROVAL" -> runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    "EVENT_APPROVAL",
                    "approval event received",
                    payload.toString()
            );
            case "SYSTEM", "TIMER" -> runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    "EVENT_" + eventType,
                    "system event handled",
                    payload.toString()
            );
            default -> runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    "EVENT_UNKNOWN",
                    "unknown event type",
                    payload.toString()
            );
        }
    }

    private JsonNode parsePayload(String payloadJson) throws Exception {
        if (payloadJson == null || payloadJson.isBlank()) {
            return objectMapper.readTree("{}");
        }
        return objectMapper.readTree(payloadJson);
    }

    private void markProcessed(Long eventId) {
        if (eventId == null) {
            return;
        }
        jdbcTemplate.update(
                "update event_inbox set status = 'PROCESSED', updated_at = current_timestamp where event_id = ?",
                eventId
        );
    }

    private void markFailed(Long eventId) {
        if (eventId == null) {
            return;
        }
        jdbcTemplate.update(
                "update event_inbox set status = 'FAILED', updated_at = current_timestamp where event_id = ?",
                eventId
        );
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignore) {
            return null;
        }
    }
}
