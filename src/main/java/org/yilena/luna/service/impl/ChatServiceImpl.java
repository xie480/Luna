package org.yilena.luna.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.aspect.LunaLogAspect;
import org.yilena.luna.constants.*;
import org.yilena.luna.entity.ChatMessage;
import org.yilena.luna.entity.ChatRequest;
import org.yilena.luna.entity.KnowledgeBase;
import org.yilena.luna.entity.Memory;
import org.yilena.luna.entity.ToolCallingContext;
import org.yilena.luna.entity.UserPreference;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.mapper.MemoryMapper;
import org.yilena.luna.mapper.UserPreferenceMapper;
import org.yilena.luna.mq.dto.SummaryMessage;
import org.yilena.luna.prompt.PromptAssembler;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.service.AgentService;
import org.yilena.luna.service.ChatService;
import org.yilena.luna.service.KnowledgeBaseService;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.sse.LunaStatusPublisher;
import org.yilena.luna.utils.ContextPruner;
import org.yilena.luna.utils.LlmClientUtil;
import org.yilena.luna.utils.ServiceCommunicateUtil;
import org.yilena.luna.utils.ToolCallingContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {
    private final PromptAssembler promptAssembler;
    private final SessionService sessionService;
    private final StringRedisTemplate stringRedisTemplate;
    private final GeminiProperty geminiProperty;
    private final LlmClientUtil llmClientUtil;
    private final KnowledgeBaseService knowledgeBaseService;
    private final LunaStatusPublisher statusPublisher;
    private final AgentService agentService;
    private final RocketMQTemplate rocketMQTemplate;
    private final UserPreferenceMapper userPreferenceMapper;
    private final MemoryMapper memoryMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final DateTimeFormatter SESSION_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    private static final int RAG_TOP_K_FETCH = 12;
    private static final int RAG_TOP_K_FINAL = 5;
    private static final long RAG_TIMEOUT_MS = 2500;

    @Override
    @LunaLogRecord(module = LogModuleConstant.CHAT, action = LogActionConstant.CHAT, type = LogType.LUNA_OUTPUT, content = "用户对话交互")
    public ResponseEntity<Object> chat(ChatRequest chatRequest) {
        String rawInput = chatRequest != null ? chatRequest.getUserInput() : null;
        log.info("收到 chat 请求，rawInputLength={}", rawInput != null ? rawInput.length() : 0);

        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING);

        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = SESSION_KEY_FORMATTER.format(today);

        String input = Optional.ofNullable(rawInput).map(Object::toString).orElse("").trim();
        if (input.isEmpty()) {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return ResponseEntity.badRequest().body("用户输入为空");
        }

        List<String> knowledgeSnippets = Collections.emptyList();
        List<String> preferenceSnippets = Collections.emptyList();
        List<String> longTermMemorySnippets = Collections.emptyList();

        try (ExecutorService vtp = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rag-vt-", 1).factory())) {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_RETRIEVING, LunaStateConstant.VALUE_RETRIEVING);

            CompletableFuture<String> queryVectorFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return llmClientUtil.getEmbedding(input);
                } catch (Exception e) {
                    return null;
                }
            }, vtp).completeOnTimeout(null, RAG_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            CompletableFuture<List<KnowledgeBase>> kbFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    List<KnowledgeBase> kbs = knowledgeBaseService.searchKnowledge(input, RAG_TOP_K_FETCH);
                    if (kbs == null || kbs.isEmpty()) return Collections.<KnowledgeBase>emptyList();
                    List<String> docs = kbs.stream().map(kb -> String.format("标题: %s\n内容: %s", kb.getTitle(), kb.getContent())).toList();
                    if (docs.size() <= RAG_TOP_K_FINAL) return kbs.stream().limit(RAG_TOP_K_FINAL).toList();
                    List<Double> scores = llmClientUtil.rerank(input, docs);
                    return llmClientUtil.rerankResources(kbs, scores, RAG_TOP_K_FINAL);
                } catch (Exception e) {
                    return Collections.<KnowledgeBase>emptyList();
                }
            }, vtp).completeOnTimeout(Collections.<KnowledgeBase>emptyList(), RAG_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            CompletableFuture<List<UserPreference>> preferenceFuture = queryVectorFuture.thenApplyAsync(queryVector -> {
                try {
                    if (queryVector == null || queryVector.isBlank()) return Collections.<UserPreference>emptyList();
                    List<UserPreference> preferences = userPreferenceMapper.searchByVector(queryVector, RAG_TOP_K_FETCH);
                    if (preferences == null || preferences.isEmpty()) return Collections.<UserPreference>emptyList();
                    List<String> docs = preferences.stream()
                            .map(p -> String.format("键: %s\n值: %s\n描述: %s", p.getPrefKey(), p.getPrefValue(), p.getDescription()))
                            .toList();
                    if (docs.size() <= RAG_TOP_K_FINAL) return preferences.stream().limit(RAG_TOP_K_FINAL).toList();
                    List<Double> scores = llmClientUtil.rerank(input, docs);
                    return llmClientUtil.rerankResources(preferences, scores, RAG_TOP_K_FINAL);
                } catch (Exception e) {
                    return Collections.<UserPreference>emptyList();
                }
            }, vtp).completeOnTimeout(Collections.<UserPreference>emptyList(), RAG_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            CompletableFuture<List<Memory>> memoryFuture = queryVectorFuture.thenApplyAsync(queryVector -> {
                try {
                    if (queryVector == null || queryVector.isBlank()) return Collections.<Memory>emptyList();
                    List<Memory> memories = memoryMapper.searchByVector(queryVector, RAG_TOP_K_FETCH);
                    if (memories == null || memories.isEmpty()) return Collections.<Memory>emptyList();
                    List<String> docs = memories.stream()
                            .map(m -> String.format("会话: %s\n类型: %s\n内容: %s", m.getSessionId(), m.getMemoryType(), m.getContent()))
                            .toList();
                    if (docs.size() <= RAG_TOP_K_FINAL) return memories.stream().limit(RAG_TOP_K_FINAL).toList();
                    List<Double> scores = llmClientUtil.rerank(input, docs);
                    return llmClientUtil.rerankResources(memories, scores, RAG_TOP_K_FINAL);
                } catch (Exception e) {
                    return Collections.<Memory>emptyList();
                }
            }, vtp).completeOnTimeout(Collections.<Memory>emptyList(), RAG_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            List<KnowledgeBase> kbs = kbFuture.join();
            List<UserPreference> preferences = preferenceFuture.join();
            List<Memory> memories = memoryFuture.join();

            if (kbs != null && !kbs.isEmpty()) {
                knowledgeSnippets = kbs.stream().map(kb -> String.format("标题: %s\n内容: %s", kb.getTitle(), kb.getContent())).collect(Collectors.toList());
            }
            if (preferences != null && !preferences.isEmpty()) {
                preferenceSnippets = preferences.stream().map(p -> String.format("偏好键: %s, 偏好值: %s, 描述: %s", p.getPrefKey(), p.getPrefValue(), p.getDescription())).toList();
            }
            if (memories != null && !memories.isEmpty()) {
                longTermMemorySnippets = memories.stream().map(m -> String.format("会话: %s, 类型: %s, 内容: %s", m.getSessionId(), m.getMemoryType(), m.getContent())).toList();
            }
        } catch (Exception e) {
            log.error("并行 RAG 总流程异常: {}", e.getMessage(), e);
        }

        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING_ORGANIZE);

        List<ChatMessage> recent = sessionService.getRecentMessages(keyPrefix, false);
        if (recent == null) recent = Collections.emptyList();

        List<String> memorySnippets = recent.stream()
                .map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime())
                .collect(Collectors.toList());

        if (ServiceCommunicateUtil.getSymbol(SymbolConstant.CONTEXT_SUMMARY_FLAG) == 1) {
            ServiceCommunicateUtil.removeSymbol(SymbolConstant.CONTEXT_SUMMARY_FLAG);

            SummaryMessage msg = SummaryMessage.builder()
                    .sessionKey(keyPrefix)
                    .memorySnippets(List.copyOf(memorySnippets))
                    .build();
            rocketMQTemplate.convertAndSend(RocketMqConstant.TOPIC_SUMMARY, msg);

            List<ChatMessage> refreshed = sessionService.getRecentMessages(keyPrefix, false);
            if (refreshed == null) refreshed = Collections.emptyList();
            memorySnippets = refreshed.stream().map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime()).collect(Collectors.toList());
        }

        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.USER, input, LocalTime.now()));

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
                .chatSessionKey(keyPrefix)
                .userInput(input)
                .memorySnippets(memorySnippets)
                .knowledgeSnippets(knowledgeSnippets)
                .build());

        String toolContext;
        try {
            toolContext = agentService.processToolCalling(keyPrefix, input);
        } finally {
            ToolCallingContextHolder.clear();
        }

        if (isAsyncPending(toolContext)) {
            String pendingReply = buildPendingReply(toolContext);
            sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, pendingReply, LocalTime.now()));
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return ResponseEntity.ok(tryParseJsonNode(pendingReply));
        }

        String prompt = promptAssembler.assembleFinalPrompt(memorySnippets, knowledgeSnippets, toolContext, input);
        SendToLuna result = getSendToLuna(prompt, input);

        LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(result.raw());
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, result.replyText(), LocalTime.now()));
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);

        return ResponseEntity.ok(tryParseJsonNode(result.valid()));
    }

    @Override
    @LunaLogRecord(module = LogModuleConstant.SYSTEM, action = LogActionConstant.STARTUP, type = LogType.SYSTEM_EVENT, content = "系统启动")
    public ResponseEntity<Object> startup() {
        log.info("收到 startup 请求");
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_STARTING, LunaStateConstant.VALUE_STARTING);

        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = SESSION_KEY_FORMATTER.format(today);
        List<ChatMessage> recent = null;

        String redisKey = String.format(RedisKeyConstant.CONTEXT_KEY_PREFIX, keyPrefix);
        if (!stringRedisTemplate.hasKey(redisKey)) {
            int index = 1;
            while (index <= 30 && (recent == null || recent.isEmpty())) {
                recent = sessionService.getRecentMessages(SESSION_KEY_FORMATTER.format(today.minusDays(index++)), true);
                if (recent == null) recent = Collections.emptyList();
            }
        } else {
            recent = sessionService.getRecentMessages(keyPrefix, false);
            if (recent == null) recent = Collections.emptyList();
        }

        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.STARTUP, "用户启动", LocalTime.now()));

        List<String> memorySnippets = recent.stream().map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime()).toList();
        String prompt = promptAssembler.assembleStartupPrompt(memorySnippets);

        SendToLuna result = getSendToLuna(prompt, "startup");
        LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(result.raw());

        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, result.replyText(), LocalTime.now()));
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);

        return ResponseEntity.ok(tryParseJsonNode(result.valid()));
    }

    @Override
    @LunaLogRecord(module = LogModuleConstant.SYSTEM, action = LogActionConstant.SHUTDOWN, type = LogType.SYSTEM_EVENT, content = "系统关闭")
    public void shutdown() {
        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = SESSION_KEY_FORMATTER.format(today);
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.SHUTDOWN, "用户关机", LocalTime.now()));
    }

    @Override
    public List<String> getHistoryDate(String yearMonth) {
        String cacheKeyPrefix = String.format(RedisKeyConstant.CONTEXT_KEY_PREFIX, yearMonth) + ":";
        ScanOptions options = ScanOptions.scanOptions().match(cacheKeyPrefix + "*").count(32).build();

        List<String> result = new ArrayList<>();
        RedisConnection connection = Objects.requireNonNull(stringRedisTemplate.getConnectionFactory()).getConnection();

        try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
            while (cursor.hasNext()) {
                String key = new String(cursor.next(), StandardCharsets.UTF_8);
                result.add(key.substring(cacheKeyPrefix.length()));
            }
        }
        return result;
    }

    @Override
    public List<String> getHistory(String yearMonthDay) {
        List<ChatMessage> chats = sessionService.getRecentMessages(yearMonthDay, true);
        return chats.stream().map(m -> m.getRole().name() + ":" + m.getContent() + ":" + m.getTime()).toList();
    }

    private SendToLuna getSendToLuna(String prompt, String originalUserInput) {
        LlmRequest request = LlmRequest.builder()
                .modelType(ModelType.OPENAI_COMPATIBLE)
                .modelName(geminiProperty.getBig().getModelName())
                .messages(List.of(LlmMessage.user(prompt)))
                .enablePromptInjectionCheck(true)
                .build();

        LlmResponse response = llmClientUtil.generate(request);
        String valid = response != null ? response.getContent() : null;

        if (valid == null) {
            log.warn("LLM 返回为空，触发本地兜底回复，scene={}", originalUserInput);
            String fallback = createFallbackJson();
            return new SendToLuna(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }

        JsonNode node = tryParseJsonNode(valid);

        if (!isValidReplyNode(node)) {
            String fallbackKey = RedisKeyConstant.GENERATE_FALLBACK_KEY;
            try {
                stringRedisTemplate.opsForValue().set(fallbackKey, "1");
            } catch (Exception ignored) {
            }

            try {
                String repairSeed = (originalUserInput != null && !originalUserInput.isBlank()) ? originalUserInput : valid;
                String repairPrompt = PromptTemplates.REPAIR_PROMPT.formatted(repairSeed);

                LlmRequest repairReq = LlmRequest.builder()
                        .modelType(ModelType.OPENAI_COMPATIBLE)
                        .modelName(geminiProperty.getBig().getModelName())
                        .messages(List.of(LlmMessage.user(repairPrompt)))
                        .enablePromptInjectionCheck(false)
                        .build();

                LlmResponse repairRes = llmClientUtil.generate(repairReq);
                String repairedText = repairRes != null ? repairRes.getContent() : null;

                if (repairedText != null) {
                    JsonNode repairedNode = tryParseJsonNode(repairedText);
                    if (isValidReplyNode(repairedNode)) {
                        String raw = repairedNode.toString();
                        String cleanJson = removeThoughtFromJson(raw);
                        return new SendToLuna(raw, cleanJson, repairedNode.get(ModelHintConstant.REPLY).asText());
                    }
                }
            } catch (Exception ignored) {
            } finally {
                stringRedisTemplate.delete(fallbackKey);
            }

            String fallback = createFallbackJson();
            return new SendToLuna(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }

        String replyText = node.get(ModelHintConstant.REPLY).asText();
        String raw = node.toString();
        String cleanValid = removeThoughtFromJson(raw);
        return new SendToLuna(raw, cleanValid, replyText);
    }

    private JsonNode tryParseJsonNode(String text) {
        if (text == null) return null;
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)```\\s*$", "").trim();
        }
        try {
            return mapper.readTree(cleaned);
        } catch (JsonProcessingException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isValidReplyNode(JsonNode node) {
        return node != null && node.hasNonNull(ModelHintConstant.REPLY) && node.get(ModelHintConstant.REPLY).isTextual();
    }

    private String createFallbackJson() {
        return "{\"thought\":\"系统降级，无法进行思考。\",\"emotion\":\"Solemn\",\"reply\":\"生成回复失败，请稍后重试。\"}";
    }

    private String extractReplyFromJsonSafe(String json) {
        JsonNode node = tryParseJsonNode(json);
        if (node != null && node.hasNonNull(ModelHintConstant.REPLY)) {
            return node.get(ModelHintConstant.REPLY).asText();
        }
        return "";
    }

    private String removeThoughtFromJson(String json) {
        try {
            JsonNode node = tryParseJsonNode(json);
            if (node != null && node.isObject()) {
                ObjectNode objectNode = (ObjectNode) node;
                objectNode.remove("thought");
                return objectNode.toString();
            }
        } catch (Exception ignored) {
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
            String skillName = node != null ? node.path("skillName").asText("该任务") : "该任务";

            ObjectNode out = mapper.createObjectNode();
            out.put("emotion", "Soft");
            out.put("reply", "Luna 已经开始处理「" + skillName + "」，任务正在后台执行。你可以继续聊天，结果出来后会第一时间通知你。");
            out.put("status", "pending");
            out.put("taskId", taskId);
            return out.toString();
        } catch (Exception e) {
            return "{\"emotion\":\"Soft\",\"reply\":\"Luna 已经开始处理任务，正在后台执行。\",\"status\":\"pending\"}";
        }
    }

    private record SendToLuna(String raw, String valid, String replyText) {
    }
}
