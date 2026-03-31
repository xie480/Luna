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
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
                "ORCHESTRATION_DECISION",
                "states selected",
                toJsonSafe(Map.of(
                        "taskState", decision == null ? null : decision.getTaskState(),
                        "relationalState", decision == null ? null : decision.getRelationalState()
                ))
        );

        List<String> knowledgeSnippets = extractTaskKnowledgeSnippets(contextPackage);
        List<String> preferenceSnippets = extractRelationalPreferenceSnippets(contextPackage);
        List<String> longTermMemorySnippets = extractTaskLongTermSnippets(contextPackage);
        List<String> workingMemorySnippets = extractWorkingMemorySnippets(contextPackage);

        try {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_RETRIEVING, LunaStateConstant.VALUE_RETRIEVING);
            RetrievalRequest retrievalRequest = RetrievalRequest.builder()
                    .query(input)
                    .sessionId(runtimeSessionId)
                    .sourceScope(List.of(RetrievalSource.KNOWLEDGE))
                    .build();
            RetrievalResponse retrievalResponse = retrievalService.retrieve(retrievalRequest);
            knowledgeSnippets = toKnowledgeSnippets(retrievalResponse);
        } catch (Exception e) {
            log.warn("rag retrieve failed: {}", e.getMessage());
        }

        List<String> memorySnippets = new ArrayList<>();
        memorySnippets.addAll(workingMemorySnippets);
        memorySnippets.addAll(extractRuntimeMessageSnippets(contextPackage));
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
                .build());

        String toolContext = null;
        long toolStartAt = System.currentTimeMillis();
        String toolStatus = "SUCCESS";
        String toolError = null;
        try {
            toolContext = agentService.processToolCalling(runtimeSessionId, input);
        } catch (Exception ex) {
            toolStatus = "FAILED";
            toolError = ex.getMessage();
            throw ex;
        } finally {
            ToolCallingContextHolder.clear();
            runtimeAuditService.persistToolExecutionTrace(
                    runtimeSessionId,
                    "agent_tool_chain",
                    toolStatus,
                    toJsonSafe(Map.of("userInput", input)),
                    toolContext,
                    toolError,
                    System.currentTimeMillis() - toolStartAt
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
                "RESPONSE_SYNTHESIS",
                "synthesis generated",
                toJsonSafe(Map.of("synthesisBrief", synthesisBrief == null ? "" : synthesisBrief))
        );

        if (isAsyncPending(mergedToolContext)) {
            String pendingReply = buildPendingReply(mergedToolContext);
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

    private List<String> toKnowledgeSnippets(RetrievalResponse response) {
        return getEvidences(response, RetrievalSource.KNOWLEDGE).stream()
                .map(evidence -> "title: " + nullSafe(evidence.getTitle()) + "\ncontent: " + nullSafe(evidence.getContent()))
                .toList();
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
