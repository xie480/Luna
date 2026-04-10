package org.yilena.luna.memory.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yilena.luna.constants.SessionConstant;
import org.yilena.luna.mapper.EventInboxMapper;
import org.yilena.luna.mapper.PerceptualBufferMapper;
import org.yilena.luna.memory.EventIngressService;
import org.yilena.luna.memory.MemoryHotLayerService;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.SessionOrchestratorService;
import org.yilena.luna.memory.model.OrchestrationDecision;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 事件接入服务默认实现，负责统一接收用户输入、工具结果、审批与系统事件，
 * 并驱动会话编排与感知缓冲写入。
 */
public class DefaultEventIngressService implements EventIngressService {

    private final EventInboxMapper eventInboxMapper;
    private final ObjectMapper objectMapper;
    private final SessionOrchestratorService sessionOrchestratorService;
    private final RuntimeAuditService runtimeAuditService;
    private final MemoryHotLayerService memoryHotLayerService;
    private final PerceptualBufferMapper perceptualBufferMapper;

    @Value("${memory.perceptual-buffer.enabled:true}")
    private boolean perceptualBufferEnabled;

    @Value("${memory.perceptual-buffer.ttl-minutes:90}")
    private int perceptualBufferTtlMinutes;

    @Override
    /**
     * 将普通用户输入包装为事件后进入统一事件通道。
     */
    public OrchestrationDecision ingestUserInput(String sessionId, String userInput) {
        return ingestEvent(sessionId, "USER_INPUT", Map.of("text", userInput == null ? "" : userInput));
    }

    @Override
    /**
     * 对带治理信号的用户输入做合法性校验，校验通过后再进入事件处理链路。
     */
    public OrchestrationDecision ingestUserInput(String sessionId, String userInput, String orchestrationSignal) {
        String normalizedSessionId = sessionId == null || sessionId.isBlank() ? SessionConstant.DEFAULT_SESSION_ID : sessionId;
        String signal = orchestrationSignal == null ? "" : orchestrationSignal.trim();
        if (signal.isBlank() || !isParseableGovernedSignal(signal)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", userInput == null ? "" : userInput);
            payload.put("orchestration_signal", signal);
            payload.put("reason", signal.isBlank() ? "orchestration_signal_missing" : "orchestration_signal_unparseable");
            runtimeAuditService.persistDecisionRecord(
                    normalizedSessionId,
                    null,
                    null,
                    "EVENT_GOVERNED_SIGNAL_INVALID",
                    "governed signal must be non-empty and parseable before orchestrator ingress",
                    toJsonSafe(payload)
            );
            return null;
        }
        return ingestEvent(sessionId, "USER_INPUT", Map.of(
                "text", userInput == null ? "" : userInput,
                "orchestration_signal", signal
        ));
    }

    @Override
    public OrchestrationDecision ingestToolResult(String sessionId, Map<String, Object> payload) {
        return ingestEvent(sessionId, "TOOL_RESULT", payload == null ? Map.of() : payload);
    }

    @Override
    public OrchestrationDecision ingestApproval(String sessionId, Map<String, Object> payload) {
        return ingestEvent(sessionId, "APPROVAL", payload == null ? Map.of() : payload);
    }

    @Override
    public OrchestrationDecision ingestSystemEvent(String sessionId, String eventType, Map<String, Object> payload) {
        return ingestEvent(sessionId, normalizeEventType(eventType), payload == null ? Map.of() : payload);
    }

    @Override
    /**
     * 批量拉取并分发待处理事件，供异步场景回补执行。
     */
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
        String normalizedSessionId = sessionId == null || sessionId.isBlank() ? SessionConstant.DEFAULT_SESSION_ID : sessionId;
        String normalizedEventType = normalizeEventType(eventType);
        try {
            JsonNode payload = parsePayload(payloadJson);
            /**
             * 按事件类型路由到对应编排入口，并同步写入运行态审计记录，
             * 让主流程和事件留痕保持一致。
             */
            OrchestrationDecision decision = switch (normalizedEventType) {
                case "USER_INPUT" -> {
                    String text = payload.path("text").asText("");
                    String orchestrationSignal = payload.path("orchestration_signal").asText("");
                    OrchestrationDecision orchestrated = sessionOrchestratorService.onUserInput(normalizedSessionId, text, orchestrationSignal);
                    runtimeAuditService.persistDecisionRecord(
                            normalizedSessionId,
                            null,
                            null,
                            "EVENT_USER_INPUT",
                            "processed from event_inbox",
                            payload.toString()
                    );
                    yield orchestrated;
                }
                case "TOOL_RESULT" -> {
                    clearPendingToolCallIfFinished(normalizedSessionId, payload);
                    OrchestrationDecision orchestrated = sessionOrchestratorService.onToolResult(normalizedSessionId, payload.toString());
                    runtimeAuditService.persistDecisionRecord(
                            normalizedSessionId,
                            null,
                            null,
                            "EVENT_TOOL_RESULT",
                            "tool result processed",
                            payload.toString()
                    );
                    yield orchestrated;
                }
                case "APPROVAL" -> {
                    OrchestrationDecision orchestrated = sessionOrchestratorService.onApproval(normalizedSessionId, payload.toString());
                    runtimeAuditService.persistDecisionRecord(
                            normalizedSessionId,
                            null,
                            null,
                            "EVENT_APPROVAL",
                            "approval event processed",
                            payload.toString()
                    );
                    yield orchestrated;
                }
                case "SYSTEM", "TIMER" -> {
                    OrchestrationDecision orchestrated = sessionOrchestratorService.onSystemEvent(normalizedSessionId, normalizedEventType, payload.toString());
                    runtimeAuditService.persistDecisionRecord(
                            normalizedSessionId,
                            null,
                            null,
                            "EVENT_" + normalizedEventType,
                            "system event handled",
                            payload.toString()
                    );
                    yield orchestrated;
                }
                default -> {
                    runtimeAuditService.persistDecisionRecord(
                            normalizedSessionId,
                            null,
                            null,
                            "EVENT_UNKNOWN",
                            "unknown event type",
                            payload.toString()
                    );
                    yield null;
                }
            };
            /**
             * 事件处理完成后补写感知缓冲并更新事件状态，
             * 便于短期感知链路快速读取最新输入信号。
             */
            writePerceptualBuffers(normalizedSessionId, normalizedEventType, payload, eventId, traceId);
            markProcessed(eventId);
            return decision;
        } catch (Exception e) {
            markFailed(eventId);
            runtimeAuditService.persistDecisionRecord(
                    normalizedSessionId,
                    null,
                    null,
                    "EVENT_FAILED",
                    e.getMessage(),
                    payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson
            );
            log.warn("event dispatch failed, eventId={}, type={}, traceId={}, err={}", eventId, eventType, traceId, e.getMessage());
            return null;
        }
    }

    private OrchestrationDecision ingestEvent(String sessionId, String eventType, Map<String, Object> payload) {
        String normalizedSessionId = sessionId == null || sessionId.isBlank() ? SessionConstant.DEFAULT_SESSION_ID : sessionId;
        String normalizedEventType = normalizeEventType(eventType);
        String traceId = UUID.randomUUID().toString();
        String payloadJson = toJsonSafe(payload);
        /**
         * 先做短时间窗口内的事件去重，
         * 避免重复回调或重复提交导致会话被多次编排。
         */
        boolean shouldProcess = memoryHotLayerService.tryDedupeEvent(normalizedSessionId, normalizedEventType, payloadJson);
        if (!shouldProcess) {
            runtimeAuditService.persistDecisionRecord(
                    normalizedSessionId,
                    null,
                    null,
                    "EVENT_DEDUPED",
                    "duplicated event skipped by redis dedupe",
                    payloadJson
            );
            return null;
        }
        Long eventId = insertPendingEvent(normalizedSessionId, eventType, payloadJson, traceId);
        if (eventId == null) {
            throw new IllegalStateException("event_inbox write failed");
        }
        /**
         * 事件入箱成功后立即复用同一处理逻辑完成本次消费，
         * 保持同步和异步两种路径的处理结果一致。
         */
        return processSingleEvent(eventId, normalizedSessionId, normalizedEventType, payloadJson, traceId);
    }

    private Long insertPendingEvent(String sessionId, String eventType, String payloadJson, String traceId) {
        try {
            return eventInboxMapper.insertPendingEvent(sessionId, eventType, payloadJson, traceId);
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

    private String toJsonSafe(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ignore) {
            return "{}";
        }
    }

    private String normalizeEventType(String eventType) {
        String text = eventType == null ? "" : eventType.trim().toUpperCase();
        return switch (text) {
            case "USER_INPUT", "TOOL_RESULT", "APPROVAL", "SYSTEM", "TIMER" -> text;
            default -> "SYSTEM";
        };
    }

    private boolean isParseableGovernedSignal(String orchestrationSignal) {
        if (orchestrationSignal == null || orchestrationSignal.isBlank()) {
            return false;
        }
        if (isJsonGovernedSignal(orchestrationSignal)) {
            return true;
        }
        return isLegacyGovernedSignal(orchestrationSignal);
    }

    private boolean isJsonGovernedSignal(String orchestrationSignal) {
        try {
            JsonNode jsonNode = objectMapper.readTree(orchestrationSignal);
            if (jsonNode == null || !jsonNode.isObject()) {
                return false;
            }
            return jsonNode.has("intent")
                    || jsonNode.has("goal")
                    || jsonNode.has("timeScope")
                    || jsonNode.has("fallback")
                    || jsonNode.has("missingSlots");
        } catch (Exception ignore) {
            return false;
        }
    }

    private boolean isLegacyGovernedSignal(String orchestrationSignal) {
        String normalized = orchestrationSignal.toLowerCase(Locale.ROOT);
        if (!normalized.contains("=")) {
            return false;
        }
        return normalized.contains("intent=")
                || normalized.contains("goal=")
                || normalized.contains("timescope=")
                || normalized.contains("fallback=")
                || normalized.contains("missingslots=");
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

    private void clearPendingToolCallIfFinished(String sessionId, JsonNode payload) {
        String status = payload.path("status").asText("").toLowerCase();
        if (!status.isBlank() && ("success".equals(status) || "completed".equals(status) || "failed".equals(status))) {
            String taskId = payload.path("taskId").asText("");
            if (!taskId.isBlank()) {
                memoryHotLayerService.clearPendingToolCall(sessionId, taskId);
            }
        }
    }

    private void writePerceptualBuffers(String sessionId,
                                        String eventType,
                                        JsonNode payload,
                                        Long eventId,
                                        String traceId) {
        /**
         * 将用户输入、工具结果及情绪/边界信号写入感知缓冲，
         * 让短期感知层能快速复用最近的重要事件。
         */
        if (!perceptualBufferEnabled) {
            return;
        }
        String baseEventRef = eventId == null ? UUID.randomUUID().toString() : String.valueOf(eventId);
        String messageRef = "event:" + baseEventRef;
        String payloadJson = payload == null ? "{}" : payload.toString();
        try {
            if ("USER_INPUT".equals(eventType) || "TOOL_RESULT".equals(eventType)) {
                String taskEventId = "TASK-" + baseEventRef;
                perceptualBufferMapper.upsertTaskBuffer(
                        taskEventId,
                        sessionId,
                        messageRef,
                        "TOOL_RESULT".equals(eventType) ? traceId : null,
                        payloadJson,
                        safeTtlMinutes()
                );
            }

            Map<String, Object> emotionSignal = extractEmotionSignal(eventType, payload);
            Map<String, Object> boundarySignal = extractBoundarySignal(eventType, payload);
            if (!emotionSignal.isEmpty() || !boundarySignal.isEmpty()) {
                String relEventId = "REL-" + baseEventRef;
                perceptualBufferMapper.upsertRelationalBuffer(
                        relEventId,
                        sessionId,
                        messageRef,
                        toJsonSafe(emotionSignal),
                        toJsonSafe(boundarySignal),
                        safeTtlMinutes()
                );
            }

            perceptualBufferMapper.deleteExpiredTaskBuffer();
            perceptualBufferMapper.deleteExpiredRelationalBuffer();
        } catch (Exception e) {
            log.debug("perceptual buffer write skipped, session={}, event={}, err={}", sessionId, eventType, e.getMessage());
        }
    }

    private Map<String, Object> extractEmotionSignal(String eventType, JsonNode payload) {
        String corpus = buildSignalCorpus(payload);
        if (corpus.isBlank()) {
            return Map.of();
        }
        boolean matched = containsAny(
                corpus,
                "anxious", "tired", "burnout", "sad", "overwhelmed", "焦虑", "难受", "崩溃", "低落", "撑不住", "压力"
        );
        if (!matched && !"APPROVAL".equals(eventType)) {
            return Map.of();
        }
        return Map.of(
                "event_type", eventType,
                "matched", matched,
                "text", summarize(corpus, 220),
                "created_from", "event_ingress"
        );
    }

    private Map<String, Object> extractBoundarySignal(String eventType, JsonNode payload) {
        String corpus = buildSignalCorpus(payload);
        if (corpus.isBlank()) {
            return Map.of();
        }
        boolean matched = containsAny(
                corpus,
                "boundary", "don't", "do not", "avoid", "uncomfortable", "别", "不要", "不喜欢", "边界", "冒犯", "先别"
        );
        boolean rejection = "APPROVAL".equals(eventType) && corpus.contains("\"approved\":false");
        if (!matched && !rejection) {
            return Map.of();
        }
        return Map.of(
                "event_type", eventType,
                "matched", matched || rejection,
                "text", summarize(corpus, 220),
                "created_from", "event_ingress"
        );
    }

    private String buildSignalCorpus(JsonNode payload) {
        if (payload == null || payload.isMissingNode()) {
            return "";
        }
        String text = payload.path("text").asText("");
        String message = payload.path("message").asText("");
        String status = payload.path("status").asText("");
        String raw = payload.toString();
        return (text + " " + message + " " + status + " " + raw).toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... words) {
        if (text == null || words == null) {
            return false;
        }
        for (String word : words) {
            if (word != null && text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private int safeTtlMinutes() {
        if (perceptualBufferTtlMinutes < 30) {
            return 30;
        }
        return Math.min(perceptualBufferTtlMinutes, 120);
    }

    private String summarize(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }
}

