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

    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd");
    private static final int RAG_TOP_K_FETCH = 12;
    private static final int RAG_TOP_K_FINAL = 5;
    private static final long RAG_TIMEOUT_MS = 2500;

    @Override
    @LunaLogRecord(module = LogModuleConstant.CHAT, action = LogActionConstant.CHAT, type = LogType.LUNA_OUTPUT, content = "用户对话交互")
    public ResponseEntity<Object> chat(ChatRequest chatRequest) {
        // 入口日志：记录用户输入，便于排查链路问题
        log.info("用户输入：{}", chatRequest.getUserInput());

        // 首次状态推送：进入思考态
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING);

        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = dateFormatter.format(today);
        String input = Optional.ofNullable(chatRequest.getUserInput())
                .map(Object::toString)
                .orElse("")
                .trim();
        if (input.isEmpty()) {
            log.error("用户输入为空");
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return ResponseEntity.badRequest().body("用户输入为空");
        }

        List<String> knowledgeSnippets = Collections.emptyList();
        List<String> preferenceSnippets = Collections.emptyList();
        List<String> longTermMemorySnippets = Collections.emptyList();

        // --- 并行 RAG 检索逻辑 (虚拟线程池 + rerank + 快速超时) ---
        // 说明：
        // 1) 并发执行向量化、知识库检索、偏好检索、长期记忆检索，减少串行等待
        // 2) 各分支设置超时兜底，避免单一路径拖慢整体响应
        // 3) 命中数量较少时跳过 rerank，减少 Python 进程调用开销
        try (ExecutorService vtp = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rag-vt-", 1).factory())) {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_RETRIEVING, LunaStateConstant.VALUE_RETRIEVING);

            CompletableFuture<String> queryVectorFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    log.debug("开始执行用户输入向量化");
                    return llmClientUtil.getEmbedding(input);
                } catch (Exception e) {
                    log.error("用户输入向量化异常: {}", e.getMessage(), e);
                    return null;
                }
            }, vtp).completeOnTimeout(null, RAG_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            CompletableFuture<List<KnowledgeBase>> kbFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    List<KnowledgeBase> kbs = knowledgeBaseService.searchKnowledge(input, RAG_TOP_K_FETCH);
                    if (kbs == null || kbs.isEmpty()) {
                        log.debug("RAG知识库检索无命中");
                        return Collections.emptyList();
                    }

                    List<String> docs = kbs.stream()
                            .map(kb -> String.format("标题: %s\n内容: %s", kb.getTitle(), kb.getContent()))
                            .toList();

                    if (docs.size() <= RAG_TOP_K_FINAL) {
                        log.info("RAG知识库命中较少({})，跳过rerank", docs.size());
                        return kbs.stream().limit(RAG_TOP_K_FINAL).toList();
                    }

                    List<Double> scores = llmClientUtil.rerank(input, docs);
                    List<KnowledgeBase> reranked = llmClientUtil.rerankResources(kbs, scores, RAG_TOP_K_FINAL);
                    log.info("RAG知识库检索命中: {} 条，rerank后保留: {}", kbs.size(), reranked.size());
                    return reranked;
                } catch (Exception e) {
                    log.error("RAG知识库检索异常: {}", e.getMessage(), e);
                    return Collections.emptyList();
                }
            }, vtp).completeOnTimeout(Collections.emptyList(), RAG_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            CompletableFuture<List<UserPreference>> preferenceFuture = queryVectorFuture.thenApplyAsync(queryVector -> {
                try {
                    if (queryVector == null || queryVector.isBlank()) {
                        log.debug("用户偏好检索跳过：queryVector为空");
                        return Collections.emptyList();
                    }

                    List<UserPreference> preferences = userPreferenceMapper.searchByVector(queryVector, RAG_TOP_K_FETCH);
                    if (preferences == null || preferences.isEmpty()) {
                        log.debug("用户偏好检索无命中");
                        return Collections.emptyList();
                    }

                    List<String> docs = preferences.stream()
                            .map(p -> String.format("键: %s\n值: %s\n描述: %s", p.getPrefKey(), p.getPrefValue(), p.getDescription()))
                            .toList();

                    if (docs.size() <= RAG_TOP_K_FINAL) {
                        log.info("用户偏好命中较少({})，跳过rerank", docs.size());
                        return preferences.stream().limit(RAG_TOP_K_FINAL).toList();
                    }

                    List<Double> scores = llmClientUtil.rerank(input, docs);
                    List<UserPreference> reranked = llmClientUtil.rerankResources(preferences, scores, RAG_TOP_K_FINAL);
                    log.info("用户偏好检索命中: {} 条，rerank后保留: {}", preferences.size(), reranked.size());
                    return reranked;
                } catch (Exception e) {
                    log.error("用户偏好检索异常: {}", e.getMessage(), e);
                    return Collections.emptyList();
                }
            }, vtp).completeOnTimeout(Collections.emptyList(), RAG_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            CompletableFuture<List<Memory>> memoryFuture = queryVectorFuture.thenApplyAsync(queryVector -> {
                try {
                    if (queryVector == null || queryVector.isBlank()) {
                        log.debug("长期记忆检索跳过：queryVector为空");
                        return Collections.emptyList();
                    }

                    List<Memory> memories = memoryMapper.searchByVector(queryVector, RAG_TOP_K_FETCH);
                    if (memories == null || memories.isEmpty()) {
                        log.debug("长期记忆检索无命中");
                        return Collections.emptyList();
                    }

                    List<String> docs = memories.stream()
                            .map(m -> String.format("会话: %s\n类型: %s\n内容: %s", m.getSessionId(), m.getMemoryType(), m.getContent()))
                            .toList();

                    if (docs.size() <= RAG_TOP_K_FINAL) {
                        log.info("长期记忆命中较少({})，跳过rerank", docs.size());
                        return memories.stream().limit(RAG_TOP_K_FINAL).toList();
                    }

                    List<Double> scores = llmClientUtil.rerank(input, docs);
                    List<Memory> reranked = llmClientUtil.rerankResources(memories, scores, RAG_TOP_K_FINAL);
                    log.info("长期记忆检索命中: {} 条，rerank后保留: {}", memories.size(), reranked.size());
                    return reranked;
                } catch (Exception e) {
                    log.error("长期记忆检索异常: {}", e.getMessage(), e);
                    return Collections.emptyList();
                }
            }, vtp).completeOnTimeout(Collections.emptyList(), RAG_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            List<KnowledgeBase> kbs = kbFuture.join();
            List<UserPreference> preferences = preferenceFuture.join();
            List<Memory> memories = memoryFuture.join();

            if (kbs != null && !kbs.isEmpty()) {
                knowledgeSnippets = kbs.stream()
                        .map(kb -> String.format("标题: %s\n内容: %s", kb.getTitle(), kb.getContent()))
                        .collect(Collectors.toList());
            }

            if (preferences != null && !preferences.isEmpty()) {
                preferenceSnippets = preferences.stream()
                        .map(p -> String.format("偏好键: %s, 偏好值: %s, 描述: %s", p.getPrefKey(), p.getPrefValue(), p.getDescription()))
                        .toList();
            }

            if (memories != null && !memories.isEmpty()) {
                longTermMemorySnippets = memories.stream()
                        .map(m -> String.format("会话: %s, 类型: %s, 内容: %s", m.getSessionId(), m.getMemoryType(), m.getContent()))
                        .toList();
            }

            log.info("并行RAG完成：knowledge={}, preference={}, memory={}",
                    knowledgeSnippets.size(), preferenceSnippets.size(), longTermMemorySnippets.size());
        } catch (Exception e) {
            log.error("并行RAG检索异常: {}", e.getMessage(), e);
        }
        // -------------------

        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING_ORGANIZE);

        List<ChatMessage> recent = sessionService.getRecentMessages(keyPrefix, false);
        if (recent == null) {
            recent = Collections.emptyList();
        }

        List<String> memorySnippets = recent.stream()
                .map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime())
                .collect(Collectors.toList());

        if (ServiceCommunicateUtil.getSymbol(SymbolConstant.CONTEXT_SUMMARY_FLAG) == 1) {
            log.info("触发上下文压缩（MQ异步），sessionKey={}。", keyPrefix);
            ServiceCommunicateUtil.removeSymbol(SymbolConstant.CONTEXT_SUMMARY_FLAG);

            SummaryMessage msg = SummaryMessage.builder()
                    .sessionKey(keyPrefix)
                    .memorySnippets(List.copyOf(memorySnippets))
                    .build();
            rocketMQTemplate.convertAndSend(RocketMqConstant.TOPIC_SUMMARY, msg);

            List<ChatMessage> refreshed = sessionService.getRecentMessages(keyPrefix, false);
            if (refreshed == null) {
                refreshed = Collections.emptyList();
            }
            memorySnippets = refreshed.stream()
                    .map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime())
                    .collect(Collectors.toList());
            log.info("上下文压缩触发后重新加载历史，条数={}", memorySnippets.size());
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

        String toolContext = agentService.processToolCalling(input);

        String prompt = promptAssembler.assembleFinalPrompt(memorySnippets, knowledgeSnippets, toolContext, input);

        SendToLuna result = getSendToLuna(prompt, input);
        log.info("整理后模型输出：{}", result.valid());

        // 覆盖 AOP 默认返回日志，保存完整原始模型输出（含 thought）
        LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(result.raw());

        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, result.replyText(), LocalTime.now()));

        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);

        return ResponseEntity.ok(tryParseJsonNode(result.valid()));
    }

    @Override
    @LunaLogRecord(module = LogModuleConstant.SYSTEM, action = LogActionConstant.STARTUP, type = LogType.SYSTEM_EVENT, content = "系统启动")
    public ResponseEntity<Object> startup() {
        log.info("开始启动流程");
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_STARTING, LunaStateConstant.VALUE_STARTING);

        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = dateFormatter.format(today);
        List<ChatMessage> recent = null;
        String redisKey = String.format(RedisKeyConstant.CONTEXT_KEY_PREFIX, keyPrefix);
        if (!stringRedisTemplate.hasKey(redisKey)) {
            int index = 1;
            while (index <= 30 && (recent == null || recent.isEmpty())) {
                recent = sessionService.getRecentMessages(dateFormatter.format(today.minusDays(index++)), true);
                if (recent == null) {
                    recent = Collections.emptyList();
                }
            }
            log.info("启动：今日无上下文，尝试加载昨日上下文，共 {} 条", recent.size());
        } else {
            recent = sessionService.getRecentMessages(keyPrefix, false);
            if (recent == null) {
                recent = Collections.emptyList();
            }
            log.info("启动：加载今日上下文，共 {} 条", recent.size());
        }
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.STARTUP, "用户启动", LocalTime.now()));
        List<String> memorySnippets = recent.stream()
                .map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime())
                .toList();
        String prompt = promptAssembler.assembleStartupPrompt(memorySnippets);
        SendToLuna result = getSendToLuna(prompt, "startup");
        log.info("整理后模型输出：{}", result.valid());

        LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(result.raw());

        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, result.replyText(), LocalTime.now()));

        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);

        return ResponseEntity.ok(tryParseJsonNode(result.valid()));
    }

    @Override
    @LunaLogRecord(module = LogModuleConstant.SYSTEM, action = LogActionConstant.SHUTDOWN, type = LogType.SYSTEM_EVENT, content = "系统关闭")
    public void shutdown() {
        log.info("开始关机流程");
        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = dateFormatter.format(today);
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.SHUTDOWN, "用户关机", LocalTime.now()));
    }

    @Override
    public List<String> getHistoryDate(String yearMonth) {
        String cacheKeyPrefix = String.format(RedisKeyConstant.CONTEXT_KEY_PREFIX, yearMonth) + ":";
        ScanOptions options = ScanOptions.scanOptions()
                .match(cacheKeyPrefix + "*")
                .count(32)
                .build();
        List<String> result = new ArrayList<>();
        RedisConnection connection = Objects.requireNonNull(stringRedisTemplate.getConnectionFactory())
                .getConnection();
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
        return chats.stream()
                .map(m -> m.getRole().name() + ":" + m.getContent() + ":" + m.getTime())
                .toList();
    }

    /**
     * 调用主模型并做结构化修复兜底
     * @param prompt 完整Prompt
     * @param originalUserInput 原始用户输入，用于修复提示词种子
     */
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
            log.error("主模型调用失败，返回降级回复");
            String fallback = createFallbackJson();
            return new SendToLuna(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }

        JsonNode node = tryParseJsonNode(valid);
        log.info("模型原始输出：{}", node != null ? node.toString() : valid);
        if (!isValidReplyNode(node)) {
            log.warn("模型输出无法解析或不包含 reply 字段，尝试修复。原始输出：{}", valid);
            String fallbackKey = RedisKeyConstant.GENERATE_FALLBACK_KEY;
            try {
                stringRedisTemplate.opsForValue().set(fallbackKey, "1");
            } catch (Exception e) {
                log.error("设置降级标记失败：{}", e.getMessage());
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
                        log.info("REPAIR_PROMPT 修复成功");
                        String raw = repairedNode.toString();
                        String cleanJson = removeThoughtFromJson(raw);
                        return new SendToLuna(raw, cleanJson, repairedNode.get(ModelHintConstant.REPLY).asText());
                    } else {
                        log.error("REPAIR_PROMPT 修复后仍不合规，repairedText={}", repairedText);
                    }
                } else {
                    log.error("REPAIR_PROMPT 未获得有效返回");
                }
            } catch (Exception ex) {
                log.error("调用 REPAIR_PROMPT 过程中发生异常：{}", ex.getMessage(), ex);
            } finally {
                stringRedisTemplate.delete(fallbackKey);
            }
            String fallback = createFallbackJson();
            log.error("模型输出最终不可用，返回降级内容：{}", fallback);
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
            cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z]*\\s*", "")
                    .replaceAll("(?s)```\\s*$", "")
                    .trim();
        }
        try {
            return mapper.readTree(cleaned);
        } catch (JsonProcessingException e) {
            log.warn("解析 JSON 失败：{}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("解析 JSON 发生意外错误：{}", e.getMessage(), e);
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

    /**
     * 对外返回时移除 thought，避免泄露内部推理文本
     */
    private String removeThoughtFromJson(String json) {
        try {
            JsonNode node = tryParseJsonNode(json);
            if (node != null && node.isObject()) {
                ObjectNode objectNode = (ObjectNode) node;
                objectNode.remove("thought");
                return objectNode.toString();
            }
        } catch (Exception e) {
            log.warn("移除 thought 字段失败：{}", e.getMessage());
        }
        return json;
    }

    private record SendToLuna(String raw, String valid, String replyText) {
    }
}
