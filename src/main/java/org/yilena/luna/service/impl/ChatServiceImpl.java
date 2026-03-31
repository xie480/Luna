package org.yilena.luna.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.aspect.LunaLogAspect;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.constants.ModelHintConstant;
import org.yilena.luna.constants.RedisKeyConstant;
import org.yilena.luna.entity.ChatMessage;
import org.yilena.luna.entity.ChatRequest;
import org.yilena.luna.entity.ToolCallingContext;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.memory.EventIngressService;
import org.yilena.luna.memory.MemoryHotLayerService;
import org.yilena.luna.memory.MemoryWritePipelineService;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.ThreeStageResponseService;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.mapper.SessionRuntimeMapper;
import org.yilena.luna.prompt.PromptAssembler;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.rag.api.RetrievalService;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.service.AgentService;
import org.yilena.luna.service.ChatService;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.sse.LunaStatusPublisher;
import org.yilena.luna.utils.AuthContextHolder;
import org.yilena.luna.utils.ContextPruner;
import org.yilena.luna.utils.LlmClientUtil;
import org.yilena.luna.utils.ToolCallingContextHolder;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final DateTimeFormatter SESSION_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    private final PromptAssembler promptAssembler;
    private final SessionService sessionService;
    private final StringRedisTemplate stringRedisTemplate;
    private final GeminiProperty geminiProperty;
    private final LlmClientUtil llmClientUtil;
    private final LunaStatusPublisher statusPublisher;
    private final AgentService agentService;
    private final RetrievalService retrievalService;
    private final EventIngressService eventIngressService;
    private final MemoryHotLayerService memoryHotLayerService;
    private final MemoryWritePipelineService memoryWritePipelineService;
    private final ThreeStageResponseService threeStageResponseService;
    private final RuntimeAuditService runtimeAuditService;
    private final SessionRuntimeMapper sessionRuntimeMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    @LunaLogRecord(module = LogModuleConstant.CHAT, action = LogActionConstant.CHAT, type = LogType.LUNA_OUTPUT, content = "chat")
    public ResponseEntity<Object> chat(ChatRequest chatRequest) {
        String input = Optional.ofNullable(chatRequest)
                .map(ChatRequest::getUserInput)
                .map(String::trim)
                .orElse("");
        if (input.isEmpty()) {
            return ResponseEntity.badRequest().body("empty input");
        }

        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING);
        String runtimeSessionId = Optional.ofNullable(AuthContextHolder.getSessionId())
                .filter(s -> !s.isBlank())
                .orElse(SESSION_KEY_FORMATTER.format(LocalDateTime.now()));

        OrchestrationDecision decision = eventIngressService.ingestUserInput(runtimeSessionId, input);
        StructuredContextPackage contextPackage = decision == null ? null : decision.getContextPackage();
        runtimeAuditService.persistContextSnapshot(runtimeSessionId, contextPackage);
        runtimeAuditService.persistDecisionRecord(
                runtimeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "ORCHESTRATION_DECISION",
                "states selected",
                toJsonSafe(buildDecisionStatePayload(decision))
        );

        List<String> knowledgeSnippets = extractTaskKnowledgeSnippets(contextPackage);
        List<String> preferenceSnippets = extractRelationalPreferenceSnippets(contextPackage);
        List<String> longTermMemorySnippets = extractTaskLongTermSnippets(contextPackage);
        List<String> workingMemorySnippets = extractWorkingMemorySnippets(contextPackage);
        List<String> ragMemorySnippets = new ArrayList<>();

        try {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_RETRIEVING, LunaStateConstant.VALUE_RETRIEVING);
            RetrievalRequest retrievalRequest = RetrievalRequest.builder()
                    .query(input)
                    .sessionId(runtimeSessionId)
                    .sourceScope(List.of(RetrievalSource.KNOWLEDGE, RetrievalSource.MEMORY, RetrievalSource.PREFERENCE))
                    .build();
            RetrievalResponse retrievalResponse = retrievalService.retrieve(retrievalRequest);
            knowledgeSnippets = toKnowledgeSnippets(retrievalResponse);
            ragMemorySnippets.addAll(toMemorySnippets(retrievalResponse));
            preferenceSnippets = mergeDistinct(preferenceSnippets, toPreferenceSnippets(retrievalResponse));
        } catch (Exception e) {
            log.warn("rag retrieve failed: {}", e.getMessage());
        }

        List<String> memorySnippets = new ArrayList<>();
        memorySnippets.addAll(workingMemorySnippets);
        memorySnippets.addAll(extractRuntimeMessageSnippets(contextPackage));
        memorySnippets.addAll(ragMemorySnippets);
        ContextPruner.ContextPayload payload = ContextPruner.ContextPayload.builder()
                .systemPrompt(PromptTemplates.SYSTEM_PROMPT)
                .userInput(input)
                .recentChatHistory(memorySnippets)
                .knowledgeBase(knowledgeSnippets)
                .userPreferences(preferenceSnippets)
                .scheduleReminders(Collections.emptyList())
                .longTermMemory(longTermMemorySnippets)
                .build();
        ContextPruner.ContextPayload pruned = ContextPruner.prune(payload);
        memorySnippets = pruned.getRecentChatHistory();
        knowledgeSnippets = pruned.getKnowledgeBase();

        ToolCallingContextHolder.set(ToolCallingContext.builder()
                .chatSessionKey(runtimeSessionId)
                .userInput(input)
                .memorySnippets(memorySnippets)
                .knowledgeSnippets(knowledgeSnippets)
                .preferenceSnippets(preferenceSnippets)
                .longTermMemorySnippets(longTermMemorySnippets)
                .toolExecutionTraces(new CopyOnWriteArrayList<>())
                .build());

        String toolContext = null;
        long toolStartAt = System.currentTimeMillis();
        String toolStatus = "SUCCESS";
        String toolError = null;
        try {
            toolContext = agentService.processToolCalling(
                    runtimeSessionId,
                    input,
                    decision == null ? null : decision.getTaskState(),
                    decision == null ? null : decision.getRelationalState()
            );
        } catch (Exception ex) {
            toolStatus = "FAILED";
            toolError = ex.getMessage();
            throw ex;
        } finally {
            List<Map<String, Object>> toolExecutionTraces = ToolCallingContextHolder.snapshotToolExecutionTraces();
            ToolCallingContextHolder.clear();
            persistToolExecutionTraces(
                    runtimeSessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    input,
                    toolContext,
                    toolStatus,
                    toolError,
                    System.currentTimeMillis() - toolStartAt,
                    toolExecutionTraces
            );
            eventIngressService.ingestToolResult(runtimeSessionId, Map.of(
                    "status", toolStatus.toLowerCase(),
                    "toolContext", toolContext == null ? "" : toolContext,
                    "error", toolError == null ? "" : toolError
            ));
        }

        String synthesisBrief = threeStageResponseService.generateSynthesisBrief(input, toolContext, contextPackage);
        String mergedToolContext = mergeToolContextWithSynthesis(toolContext, synthesisBrief);
        runtimeAuditService.persistDecisionRecord(
                runtimeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "RESPONSE_SYNTHESIS",
                "synthesis generated",
                toJsonSafe(Map.of("synthesisBrief", synthesisBrief == null ? "" : synthesisBrief))
        );

        if (isAsyncPending(mergedToolContext)) {
            String pendingReply = buildPendingReply(mergedToolContext);
            cachePendingToolCall(runtimeSessionId, mergedToolContext);
            memoryWritePipelineService.writeAfterTurn(runtimeSessionId, input, pendingReply, contextPackage);
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return ResponseEntity.ok(tryParseJsonNode(pendingReply));
        }

        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING_ORGANIZE);
        SendToLuna result;
        String threeStageFinal = threeStageResponseService.generateFinalResponse(input, mergedToolContext, contextPackage);
        JsonNode threeStageNode = tryParseJsonNode(threeStageFinal);
        if (isValidReplyNode(threeStageNode)) {
            String raw = threeStageNode.toString();
            result = new SendToLuna(raw, removeThoughtFromJson(raw), threeStageNode.get(ModelHintConstant.REPLY).asText());
        } else {
            String prompt = promptAssembler.assembleFinalPrompt(
                    memorySnippets,
                    knowledgeSnippets,
                    preferenceSnippets,
                    longTermMemorySnippets,
                    mergedToolContext,
                    input
            );
            result = getSendToLuna(prompt, input, contextPackage);
        }
        LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(result.raw());
        memoryWritePipelineService.writeAfterTurn(runtimeSessionId, input, result.replyText(), contextPackage);
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
        return ResponseEntity.ok(tryParseJsonNode(result.valid()));
    }

    @Override
    @LunaLogRecord(module = LogModuleConstant.SYSTEM, action = LogActionConstant.STARTUP, type = LogType.SYSTEM_EVENT, content = "startup")
    public ResponseEntity<Object> startup() {
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_STARTING, LunaStateConstant.VALUE_STARTING);
        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = SESSION_KEY_FORMATTER.format(today);

        List<ChatMessage> recent = sessionService.getRecentMessages(keyPrefix, false);
        if (recent == null) {
            recent = Collections.emptyList();
        }
        int index = 1;
        while (index <= 30 && recent.isEmpty()) {
            recent = sessionService.getRecentMessages(SESSION_KEY_FORMATTER.format(today.minusDays(index++)), true);
            if (recent == null) {
                recent = Collections.emptyList();
            }
        }

        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.STARTUP, "startup", LocalTime.now()));
        List<String> memorySnippets = recent.stream()
                .map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime())
                .toList();
        String prompt = promptAssembler.assembleStartupPrompt(memorySnippets);
        SendToLuna result = getSendToLuna(prompt, "startup", null);
        LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(result.raw());
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, result.replyText(), LocalTime.now()));
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
        return ResponseEntity.ok(tryParseJsonNode(result.valid()));
    }

    @Override
    @LunaLogRecord(module = LogModuleConstant.SYSTEM, action = LogActionConstant.SHUTDOWN, type = LogType.SYSTEM_EVENT, content = "shutdown")
    public void shutdown() {
        String keyPrefix = SESSION_KEY_FORMATTER.format(LocalDateTime.now());
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.SHUTDOWN, "shutdown", LocalTime.now()));
    }

    @Override
    public List<String> getHistoryDate(String yearMonth) {
        List<String> result = new ArrayList<>();
        String prefix = (yearMonth == null ? "" : yearMonth.trim()) + ":";
        if (prefix.length() < 8) {
            return result;
        }
        List<Map<String, Object>> rows = sessionRuntimeMapper.selectDistinctSessionIdsLike(prefix + "%");
        for (Map<String, Object> row : rows) {
            String sessionId = String.valueOf(row.get("session_id"));
            if (sessionId.startsWith(prefix) && sessionId.length() > prefix.length()) {
                result.add(sessionId.substring(prefix.length()));
            }
        }
        return result;
    }

    @Override
    public List<String> getHistory(String yearMonthDay) {
        List<ChatMessage> chats = sessionService.getRecentMessages(yearMonthDay, true);
        if (chats == null) {
            return Collections.emptyList();
        }
        return chats.stream().map(m -> m.getRole().name() + ":" + m.getContent() + ":" + m.getTime()).toList();
    }

    private SendToLuna getSendToLuna(String prompt, String originalUserInput, StructuredContextPackage contextPackage) {
        String executionModelName = resolveExecutionModelName(contextPackage);
        LlmRequest request = LlmRequest.builder()
                .modelType(ModelType.OPENAI_COMPATIBLE)
                .modelName(executionModelName)
                .messages(List.of(LlmMessage.user(prompt)))
                .enablePromptInjectionCheck(true)
                .build();

        LlmResponse response = llmClientUtil.generate(request);
        String valid = response != null ? response.getContent() : null;
        if (valid == null) {
            String fallback = createFallbackJson();
            return new SendToLuna(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }

        JsonNode node = tryParseJsonNode(valid);
        if (!isValidReplyNode(node)) {
            String fallbackKey = RedisKeyConstant.GENERATE_FALLBACK_KEY;
            try {
                stringRedisTemplate.opsForValue().set(fallbackKey, "1");
                String repairPrompt = PromptTemplates.REPAIR_PROMPT.formatted(originalUserInput == null ? valid : originalUserInput);
                LlmRequest repairReq = LlmRequest.builder()
                        .modelType(ModelType.OPENAI_COMPATIBLE)
                        .modelName(executionModelName)
                        .messages(List.of(LlmMessage.user(repairPrompt)))
                        .enablePromptInjectionCheck(false)
                        .build();
                LlmResponse repairRes = llmClientUtil.generate(repairReq);
                String repairedText = repairRes != null ? repairRes.getContent() : null;
                if (repairedText != null) {
                    JsonNode repairedNode = tryParseJsonNode(repairedText);
                    if (isValidReplyNode(repairedNode)) {
                        String raw = repairedNode.toString();
                        return new SendToLuna(raw, removeThoughtFromJson(raw), repairedNode.get(ModelHintConstant.REPLY).asText());
                    }
                }
            } catch (Exception ignore) {
            } finally {
                stringRedisTemplate.delete(fallbackKey);
            }
            String fallback = createFallbackJson();
            return new SendToLuna(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }

        String raw = node.toString();
        return new SendToLuna(raw, removeThoughtFromJson(raw), node.get(ModelHintConstant.REPLY).asText());
    }

    private String resolveExecutionModelName(StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return geminiProperty.getBig().getModelName();
        }
        TaskRuntimeState taskState = contextPackage.getTaskState();
        RelationalRuntimeState relationalState = contextPackage.getRelationalState();
        if ((taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING || taskState == TaskRuntimeState.EXECUTING)
                && geminiProperty.getCode() != null && geminiProperty.getCode().getModelName() != null) {
            return geminiProperty.getCode().getModelName();
        }
        if ((relationalState == RelationalRuntimeState.EMOTIONAL_SUPPORT
                || relationalState == RelationalRuntimeState.FRAGILE_MOMENT
                || relationalState == RelationalRuntimeState.REPAIRING)
                && geminiProperty.getChat() != null && geminiProperty.getChat().getModelName() != null) {
            return geminiProperty.getChat().getModelName();
        }
        if (geminiProperty.getBig() != null && geminiProperty.getBig().getModelName() != null) {
            return geminiProperty.getBig().getModelName();
        }
        return geminiProperty.getFlash().getModelName();
    }

    private Long contextPlanId(StructuredContextPackage contextPackage) {
        try {
            if (contextPackage == null || contextPackage.getRuntime() == null) {
                return null;
            }
            Object session = contextPackage.getRuntime().get("session");
            if (session instanceof Map<?, ?> row) {
                return toLong(row.get("current_plan_id"));
            }
            return null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long contextNodeId(StructuredContextPackage contextPackage) {
        try {
            if (contextPackage == null || contextPackage.getTaskContext() == null) {
                return null;
            }
            Object working = contextPackage.getTaskContext().get("working_memory");
            if (working instanceof Map<?, ?> row) {
                return toLong(row.get("active_node_id"));
            }
            return null;
        } catch (Exception ignore) {
            return null;
        }
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

    private JsonNode tryParseJsonNode(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)```\\s*$", "").trim();
        }
        try {
            return mapper.readTree(cleaned);
        } catch (Exception ignore) {
            return null;
        }
    }

    private boolean isValidReplyNode(JsonNode node) {
        return node != null && node.hasNonNull(ModelHintConstant.REPLY) && node.get(ModelHintConstant.REPLY).isTextual();
    }

    private String createFallbackJson() {
        return "{\"thought\":\"fallback\",\"emotion\":\"Solemn\",\"reply\":\"please try again\"}";
    }

    private String extractReplyFromJsonSafe(String json) {
        JsonNode node = tryParseJsonNode(json);
        return node != null && node.hasNonNull(ModelHintConstant.REPLY) ? node.get(ModelHintConstant.REPLY).asText() : "";
    }

    private String removeThoughtFromJson(String json) {
        try {
            JsonNode node = tryParseJsonNode(json);
            if (node != null && node.isObject()) {
                ObjectNode objectNode = (ObjectNode) node;
                objectNode.remove("thought");
                return objectNode.toString();
            }
        } catch (Exception ignore) {
        }
        return json;
    }

    private boolean isAsyncPending(String toolContext) {
        JsonNode node = tryParseJsonNode(toolContext);
        return node != null && "pending".equalsIgnoreCase(node.path("status").asText(""));
    }

    private String buildPendingReply(String toolContext) {
        try {
            JsonNode node = tryParseJsonNode(toolContext);
            String taskId = node != null ? node.path("taskId").asText("") : "";
            String skillName = node != null ? node.path("skillName").asText("task") : "task";
            ObjectNode out = mapper.createObjectNode();
            out.put("emotion", "Soft");
            out.put("reply", "Luna is processing " + skillName + ". You can continue chatting, result will arrive soon.");
            out.put("status", "pending");
            out.put("taskId", taskId);
            return out.toString();
        } catch (Exception e) {
            return "{\"emotion\":\"Soft\",\"reply\":\"task is running in background\",\"status\":\"pending\"}";
        }
    }

    private void cachePendingToolCall(String sessionId, String toolContext) {
        JsonNode node = tryParseJsonNode(toolContext);
        if (node == null) {
            return;
        }
        String taskId = node.path("taskId").asText("");
        if (taskId.isBlank()) {
            return;
        }
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("taskId", taskId);
        payload.put("skillName", node.path("skillName").asText(""));
        payload.put("status", "pending");
        payload.put("toolContext", toolContext == null ? "" : toolContext);
        memoryHotLayerService.putPendingToolCall(sessionId, taskId, payload);
    }

    private List<String> extractTaskKnowledgeSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return Collections.emptyList();
        }
        Object raw = contextPackage.getTaskContext().get("knowledge");
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
        return rows.stream()
                .map(item -> "title: " + nullSafe(stringValue(item.get("title"))) + "\ncontent: " + nullSafe(stringValue(item.get("chunk_text"))))
                .toList();
    }

    private List<String> extractTaskLongTermSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return Collections.emptyList();
        }
        List<String> snippets = new ArrayList<>();
        Object factsRaw = contextPackage.getTaskContext().get("task_facts");
        if (factsRaw instanceof List<?> facts) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) facts;
            snippets.addAll(rows.stream()
                    .map(item -> "task_fact: " + nullSafe(stringValue(item.get("fact_key"))) + "=" + nullSafe(stringValue(item.get("fact_value_text"))))
                    .toList());
        }
        Object episodesRaw = contextPackage.getTaskContext().get("task_episodes");
        if (episodesRaw instanceof List<?> episodes) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) episodes;
            snippets.addAll(rows.stream()
                    .map(item -> "task_episode: " + nullSafe(stringValue(item.get("episode_type"))) + " | " + nullSafe(stringValue(item.get("trajectory_summary"))))
                    .toList());
        }
        return snippets;
    }

    private List<String> extractWorkingMemorySnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return Collections.emptyList();
        }
        Object raw = contextPackage.getTaskContext().get("working_memory");
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        out.add("working.goal_raw: " + nullSafe(stringValue(map.get("goal_raw"))));
        out.add("working.goal_refined: " + nullSafe(stringValue(map.get("goal_refined"))));
        out.add("working.unresolved_questions: " + nullSafe(stringValue(map.get("unresolved_questions_json"))));
        out.add("working.risks: " + nullSafe(stringValue(map.get("risks_json"))));
        return out;
    }

    private List<String> extractRelationalPreferenceSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRelationalContext() == null) {
            return Collections.emptyList();
        }
        Object raw = contextPackage.getRelationalContext().get("semantic_facts");
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
        return rows.stream()
                .map(item -> "relation_pref: " + nullSafe(stringValue(item.get("fact_key"))) + "=" + nullSafe(stringValue(item.get("fact_value_text"))))
                .toList();
    }

    private List<String> extractRuntimeMessageSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRuntime() == null) {
            return Collections.emptyList();
        }
        Object raw = contextPackage.getRuntime().get("recent_messages");
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
        return rows.stream()
                .map(item -> nullSafe(stringValue(item.get("role"))) + ": " + nullSafe(stringValue(item.get("content_text"))))
                .toList();
    }

    private String mergeToolContextWithSynthesis(String toolContext, String synthesisBrief) {
        String brief = synthesisBrief == null ? "" : synthesisBrief.trim();
        if (brief.isEmpty()) {
            return toolContext;
        }
        try {
            JsonNode node = tryParseJsonNode(toolContext);
            if (node != null && node.isObject()) {
                ObjectNode objectNode = (ObjectNode) node;
                objectNode.put("three_stage_synthesis_brief", brief);
                return objectNode.toString();
            }
        } catch (Exception ignore) {
        }
        String base = toolContext == null || toolContext.isBlank() ? "{}" : toolContext;
        return base + "\n\n[THREE_STAGE_SYNTHESIS_BRIEF]\n" + brief;
    }

    private void persistToolExecutionTraces(String sessionId,
                                            Long planId,
                                            Long nodeId,
                                            String userInput,
                                            String toolContext,
                                            String chainStatus,
                                            String chainError,
                                            long chainLatencyMs,
                                            List<Map<String, Object>> traces) {
        List<Map<String, Object>> safeTraces = traces == null ? List.of() : traces;
        if (safeTraces.isEmpty()) {
            runtimeAuditService.persistToolExecutionTrace(
                    sessionId,
                    planId,
                    nodeId,
                    "agent_tool_chain",
                    chainStatus,
                    toJsonSafe(Map.of("userInput", userInput == null ? "" : userInput)),
                    toolContext,
                    chainError,
                    Math.max(0L, chainLatencyMs)
            );
            return;
        }

        int sequence = 1;
        for (Map<String, Object> trace : safeTraces) {
            Map<String, Object> normalizedInput = new LinkedHashMap<>();
            normalizedInput.put("sequence", sequence);
            normalizedInput.put("source_type", stringValue(trace.get("source_type")));
            normalizedInput.put("payload", trace.getOrDefault("normalized_input", Map.of()));

            Map<String, Object> normalizedOutput = new LinkedHashMap<>();
            normalizedOutput.put("sequence", sequence);
            normalizedOutput.put("source_type", stringValue(trace.get("source_type")));
            normalizedOutput.put("payload", trace.getOrDefault("normalized_output", Map.of()));

            runtimeAuditService.persistToolExecutionTrace(
                    sessionId,
                    planId,
                    nodeId,
                    normalizeToolName(trace.get("tool_name"), sequence),
                    normalizeCallStatus(trace.get("call_status")),
                    toJsonSafe(normalizedInput),
                    toJsonSafe(normalizedOutput),
                    stringValue(trace.get("error_message")),
                    normalizeLatency(trace.get("latency_ms"))
            );
            sequence++;
        }

        runtimeAuditService.persistToolExecutionTrace(
                sessionId,
                planId,
                nodeId,
                "agent_tool_chain",
                chainStatus,
                toJsonSafe(Map.of(
                        "userInput", userInput == null ? "" : userInput,
                        "traceCount", safeTraces.size()
                )),
                toJsonSafe(Map.of(
                        "toolContext", toolContext == null ? "" : toolContext,
                        "chainStatus", chainStatus == null ? "" : chainStatus
                )),
                chainError,
                Math.max(0L, chainLatencyMs)
        );
    }

    private String normalizeToolName(Object rawName, int sequence) {
        String name = stringValue(rawName);
        if (name == null || name.isBlank()) {
            return "tool_call_" + sequence;
        }
        return name;
    }

    private String normalizeCallStatus(Object rawStatus) {
        String status = stringValue(rawStatus);
        if (status == null || status.isBlank()) {
            return "UNKNOWN";
        }
        return status.toUpperCase();
    }

    private Long normalizeLatency(Object rawLatency) {
        Long value = toLong(rawLatency);
        if (value == null) {
            return null;
        }
        return Math.max(0L, value);
    }

    private Map<String, Object> buildDecisionStatePayload(OrchestrationDecision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskState", decision == null || decision.getTaskState() == null ? "" : decision.getTaskState().name());
        payload.put("relationalState", decision == null || decision.getRelationalState() == null ? "" : decision.getRelationalState().name());
        return payload;
    }

    private List<String> toKnowledgeSnippets(RetrievalResponse response) {
        return getEvidences(response, RetrievalSource.KNOWLEDGE).stream()
                .map(evidence -> "title: " + nullSafe(evidence.getTitle()) + "\ncontent: " + nullSafe(evidence.getContent()))
                .toList();
    }

    private List<String> toMemorySnippets(RetrievalResponse response) {
        return getEvidences(response, RetrievalSource.MEMORY).stream()
                .map(evidence -> "memory: " + nullSafe(evidence.getContent()))
                .toList();
    }

    private List<String> toPreferenceSnippets(RetrievalResponse response) {
        return getEvidences(response, RetrievalSource.PREFERENCE).stream()
                .map(evidence -> "preference: " + nullSafe(evidence.getContent()))
                .toList();
    }

    private List<String> mergeDistinct(List<String> left, List<String> right) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return new ArrayList<>(merged);
    }

    private List<Evidence> getEvidences(RetrievalResponse response, RetrievalSource source) {
        if (response == null || response.getEvidences() == null) {
            return Collections.emptyList();
        }
        return response.getEvidences().getOrDefault(source, Collections.emptyList());
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String toJsonSafe(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ignore) {
            return "{}";
        }
    }

    private record SendToLuna(String raw, String valid, String replyText) {
    }
}
