package org.yilena.luna.memory.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.mapper.EventInboxMapper;
import org.yilena.luna.memory.EventIngressService;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.SessionOrchestratorService;
import org.yilena.luna.memory.model.OrchestrationDecision;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultEventIngressService implements EventIngressService {

    private final EventInboxMapper eventInboxMapper;
    private final ObjectMapper objectMapper;
    private final SessionOrchestratorService sessionOrchestratorService;
    private final RuntimeAuditService runtimeAuditService;

    @Override
    public OrchestrationDecision ingestUserInput(String sessionId, String userInput) {
        String normalizedSessionId = sessionId == null || sessionId.isBlank() ? "default-session" : sessionId;
        String traceId = UUID.randomUUID().toString();
        Long eventId = insertPendingEvent(normalizedSessionId, "USER_INPUT", userInput, traceId);
        if (eventId == null) {
            throw new IllegalStateException("event_inbox write failed");
        }
        return processSingleEvent(eventId, normalizedSessionId, "USER_INPUT", payloadOfText(userInput), traceId);
    }

    @Override
    public void dispatchPendingEvents(int limit) {
        List<Map<String, Object>> events = fetchPendingEvents(limit <= 0 ? 50 : limit);
        for (Map<String, Object> event : events) {
            Long eventId = toLong(event.get("event_id"));
            String sessionId = str(event.get("session_id"));
            String eventType = str(event.get("event_type"));
            String payloadJson = str(event.get("payload_json"));
            String traceId = str(event.get("trace_id"));
            processSingleEvent(eventId, sessionId, eventType, payloadJson, traceId);
        }
    }

    private OrchestrationDecision processSingleEvent(Long eventId,
                                                     String sessionId,
                                                     String eventType,
                                                     String payloadJson,
                                                     String traceId) {
        String normalizedSessionId = sessionId == null || sessionId.isBlank() ? "default-session" : sessionId;
        try {
            JsonNode payload = parsePayload(payloadJson);
            OrchestrationDecision decision = switch (eventType) {
                case "USER_INPUT" -> {
                    String text = payload.path("text").asText("");
                    OrchestrationDecision orchestrated = sessionOrchestratorService.onUserInput(normalizedSessionId, text);
                    runtimeAuditService.persistDecisionRecord(
                            normalizedSessionId,
                            "EVENT_USER_INPUT",
                            "processed from event_inbox",
                            payload.toString()
                    );
                    yield orchestrated;
                }
                case "TOOL_RESULT" -> {
                    runtimeAuditService.persistDecisionRecord(
                            normalizedSessionId,
                            "EVENT_TOOL_RESULT",
                            "tool callback received",
                            payload.toString()
                    );
                    yield null;
                }
                case "APPROVAL" -> {
                    runtimeAuditService.persistDecisionRecord(
                            normalizedSessionId,
                            "EVENT_APPROVAL",
                            "approval event received",
                            payload.toString()
                    );
                    yield null;
                }
                case "SYSTEM", "TIMER" -> {
                    runtimeAuditService.persistDecisionRecord(
                            normalizedSessionId,
                            "EVENT_" + eventType,
                            "system event handled",
                            payload.toString()
                    );
                    yield null;
                }
                default -> {
                    runtimeAuditService.persistDecisionRecord(
                            normalizedSessionId,
                            "EVENT_UNKNOWN",
                            "unknown event type",
                            payload.toString()
                    );
                    yield null;
                }
            };
            markProcessed(eventId);
            return decision;
        } catch (Exception e) {
            markFailed(eventId);
            runtimeAuditService.persistDecisionRecord(
                    normalizedSessionId,
                    "EVENT_FAILED",
                    e.getMessage(),
                    payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson
            );
            log.warn("event dispatch failed, eventId={}, type={}, traceId={}, err={}", eventId, eventType, traceId, e.getMessage());
            return null;
        }
    }

    private Long insertPendingEvent(String sessionId, String eventType, String text, String traceId) {
        try {
            return eventInboxMapper.insertPendingEvent(sessionId, eventType, text, traceId);
        } catch (Exception ignore) {
            return null;
        }
    }

    private List<Map<String, Object>> fetchPendingEvents(int limit) {
        try {
            return eventInboxMapper.selectPendingEvents(limit);
        } catch (Exception ignore) {
            return List.of();
        }
    }

    private JsonNode parsePayload(String payloadJson) throws Exception {
        if (payloadJson == null || payloadJson.isBlank()) {
            return objectMapper.readTree("{}");
        }
        return objectMapper.readTree(payloadJson);
    }

    private String payloadOfText(String text) {
        try {
            return objectMapper.writeValueAsString(Map.of("text", text == null ? "" : text));
        } catch (Exception ignore) {
            return "{\"text\":\"\"}";
        }
    }

    private void markProcessed(Long eventId) {
        if (eventId == null) {
            return;
        }
        eventInboxMapper.markProcessed(eventId);
    }

    private void markFailed(Long eventId) {
        if (eventId == null) {
            return;
        }
        eventInboxMapper.markFailed(eventId);
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
