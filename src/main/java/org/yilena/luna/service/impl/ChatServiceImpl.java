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
import org.yilena.luna.entity.ToolCallingContext;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.mq.dto.SummaryMessage;
import org.yilena.luna.memory.MemoryWritePipelineService;
import org.yilena.luna.memory.SessionOrchestratorService;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
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
import org.yilena.luna.utils.ServiceCommunicateUtil;
import org.yilena.luna.utils.ToolCallingContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * ChatServiceImpl ??
 */
public class ChatServiceImpl implements ChatService {
    // Prompt 组装器：负责拼接 system/memory/rag/tool/userInput 等上下文块
    private final PromptAssembler promptAssembler;
    // 会话存储服务：读写当日会话与历史会话
    private final SessionService sessionService;
    // Redis 主要用于会话缓存与降级修复标记
    private final StringRedisTemplate stringRedisTemplate;
    // 当前主模型配置（chat 阶段使用 big 模型）
    private final GeminiProperty geminiProperty;
    // LLM 统一调用入口（含常规 generate/embedding/rerank）
    private final LlmClientUtil llmClientUtil;
    // SSE 发布器：向前端持续推送 LUNA 当前状态
    private final LunaStatusPublisher statusPublisher;
    // 工具路由与执行服务
    private final AgentService agentService;
    // MQ：异步摘要任务投递
    private final RocketMQTemplate rocketMQTemplate;
    // 新的通用 RAG 统一入口（已从 ChatService 中解耦）
    private final RetrievalService retrievalService;
    private final SessionOrchestratorService sessionOrchestratorService;
    private final MemoryWritePipelineService memoryWritePipelineService;
    // 局部 JSON 处理器，仅在本类用于解析/构造响应片段
    private final ObjectMapper mapper = new ObjectMapper();

    // 统一会话日期键格式：yyyy:MM:dd
    private static final DateTimeFormatter SESSION_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    @Override
    @LunaLogRecord(module = LogModuleConstant.CHAT, action = LogActionConstant.CHAT, type = LogType.LUNA_OUTPUT, content = "用户对话交互")
    public ResponseEntity<Object> chat(ChatRequest chatRequest) {
        // 1) 基础参数读取与状态进入（THINKING）
        String rawInput = chatRequest != null ? chatRequest.getUserInput() : null;
        log.info("收到 chat 请求，rawInputLength={}", rawInput != null ? rawInput.length() : 0);

        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING);

        // 以当天为维度组织会话 key；历史查询也沿用同一规则
        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = SESSION_KEY_FORMATTER.format(today);

        String input = Optional.ofNullable(rawInput).map(Object::toString).orElse("").trim();
        if (input.isEmpty()) {
            // 空输入直接返回 400，并恢复前端状态为 IDLE
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return ResponseEntity.badRequest().body("用户输入为空");
        }

        // RAG 三类上下文片段（知识/偏好/长期记忆）
        List<String> knowledgeSnippets = Collections.emptyList();
        String runtimeSessionId = Optional.ofNullable(AuthContextHolder.getSessionId())
                .filter(s -> !s.isBlank())
                .orElse(keyPrefix);
        OrchestrationDecision orchestrationDecision = sessionOrchestratorService.onUserInput(runtimeSessionId, input);
        StructuredContextPackage contextPackage = orchestrationDecision.getContextPackage();
        knowledgeSnippets = extractTaskKnowledgeSnippets(contextPackage);
        List<String> preferenceSnippets = Collections.emptyList();
        List<String> longTermMemorySnippets = extractTaskLongTermSnippets(contextPackage);

        try {
            // 2) RAG 检索阶段：由统一 RetrievalService 负责路由/召回/rerank/结构化返回
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_RETRIEVING, LunaStateConstant.VALUE_RETRIEVING);
            // 鉴权层写入的 jti 作为稳定 sessionId，用于 memory source 精准过滤
            String sessionId = runtimeSessionId;
            RetrievalRequest retrievalRequest = RetrievalRequest.builder()
                    .query(input)
                    .sessionId(sessionId)
                    .build();
            RetrievalResponse retrievalResponse = retrievalService.retrieve(retrievalRequest);
            // 将统一 evidence 转成现有 prompt 体系可消费的字符串片段
            knowledgeSnippets = toKnowledgeSnippets(retrievalResponse);
            preferenceSnippets = mergePreferenceSnippets(preferenceSnippets, toPreferenceSnippets(retrievalResponse));
            longTermMemorySnippets = mergeMemorySnippets(longTermMemorySnippets, toMemorySnippets(retrievalResponse));
        } catch (Exception e) {
            // RAG 失败不阻断主对话链路，按“无 RAG 片段”继续
            log.error("并行 RAG 总流程异常: {}", e.getMessage(), e);
        }

        // 3) 进入“组织回复”阶段
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING_ORGANIZE);

        // 读取最近对话上下文（短期记忆）
        List<ChatMessage> recent = sessionService.getRecentMessages(keyPrefix, false);
        if (recent == null) recent = Collections.emptyList();

        // 统一转成 prompt 所需的文本格式
        List<String> memorySnippets = recent.stream()
                .map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime())
                .collect(Collectors.toList());
        memorySnippets.addAll(extractRuntimeMessageSnippets(contextPackage));

        // 4) 若触发上下文摘要标记，则异步投递摘要任务并刷新会话视图
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

        // 先写入用户输入，保证会话事件顺序（USER -> LUNA）
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.USER, input, LocalTime.now()));

        // 5) 上下文裁剪：当总长度超阈值时按优先级淘汰低优先内容
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

        // 6) 为工具调用阶段写入 ThreadLocal 上下文，供下游工具链按需读取
        ToolCallingContextHolder.set(ToolCallingContext.builder()
                .chatSessionKey(keyPrefix)
                .userInput(input)
                .memorySnippets(memorySnippets)
                .knowledgeSnippets(knowledgeSnippets)
                .preferenceSnippets(preferenceSnippets)
                .longTermMemorySnippets(longTermMemorySnippets)
                .build());

        String toolContext;
        try {
            // 工具执行可能返回同步结果，也可能返回异步 pending 标记
            toolContext = agentService.processToolCalling(keyPrefix, input);
        } finally {
            // 必须清理 ThreadLocal，防止请求串脏数据
            ToolCallingContextHolder.clear();
        }

        // 7) 异步任务场景：直接给用户 pending 提示，不阻塞当前对话请求
        if (isAsyncPending(toolContext)) {
            String pendingReply = buildPendingReply(toolContext);
            sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, pendingReply, LocalTime.now()));
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return ResponseEntity.ok(tryParseJsonNode(pendingReply));
        }

        // 8) 正常生成阶段：组装最终 prompt -> 调模型 -> 写会话 -> 返回
        String prompt = promptAssembler.assembleFinalPrompt(
                memorySnippets,
                knowledgeSnippets,
                preferenceSnippets,
                longTermMemorySnippets,
                toolContext,
                input
        );
        SendToLuna result = getSendToLuna(prompt, input);

        // AOP 日志模块通过该 ThreadLocal 覆盖默认响应内容
        LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(result.raw());
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, result.replyText(), LocalTime.now()));
        memoryWritePipelineService.writeAfterTurn(runtimeSessionId, input, result.replyText(), contextPackage);
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);

        return ResponseEntity.ok(tryParseJsonNode(result.valid()));
    }

    @Override
    @LunaLogRecord(module = LogModuleConstant.SYSTEM, action = LogActionConstant.STARTUP, type = LogType.SYSTEM_EVENT, content = "系统启动")
    public ResponseEntity<Object> startup() {
        // 开机流程：尝试恢复最近历史上下文并生成开机场景回复
        log.info("收到 startup 请求");
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_STARTING, LunaStateConstant.VALUE_STARTING);

        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = SESSION_KEY_FORMATTER.format(today);
        List<ChatMessage> recent = null;

        String redisKey = String.format(RedisKeyConstant.CONTEXT_KEY_PREFIX, keyPrefix);
        if (!stringRedisTemplate.hasKey(redisKey)) {
            // 今天没缓存则向前回溯最多 30 天，找到第一批可用历史
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
        // 关机流程只记录事件，不做生成
        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = SESSION_KEY_FORMATTER.format(today);
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.SHUTDOWN, "用户关机", LocalTime.now()));
    }

    @Override
    public List<String> getHistoryDate(String yearMonth) {
        // Redis scan 查询指定年月下存在会话的日期列表
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
        // 历史接口直接返回“角色:内容:时间”简格式
        List<ChatMessage> chats = sessionService.getRecentMessages(yearMonthDay, true);
        if (chats == null) {
            return Collections.emptyList();
        }
        return chats.stream().map(m -> m.getRole().name() + ":" + m.getContent() + ":" + m.getTime()).toList();
    }

    private SendToLuna getSendToLuna(String prompt, String originalUserInput) {
        // 统一模型调用请求：默认启用 prompt 注入检查
        LlmRequest request = LlmRequest.builder()
                .modelType(ModelType.OPENAI_COMPATIBLE)
                .modelName(geminiProperty.getBig().getModelName())
                .messages(List.of(LlmMessage.user(prompt)))
                .enablePromptInjectionCheck(true)
                .build();

        LlmResponse response = llmClientUtil.generate(request);
        String valid = response != null ? response.getContent() : null;

        if (valid == null) {
            // 模型空响应直接降级，避免对话中断
            log.warn("LLM 返回为空，触发本地兜底回复，scene={}", originalUserInput);
            String fallback = createFallbackJson();
            return new SendToLuna(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }

        JsonNode node = tryParseJsonNode(valid);

        if (!isValidReplyNode(node)) {
            // 返回结构不合法时进入“修复模式”：二次 prompt 纠正 JSON 结构
            String fallbackKey = RedisKeyConstant.GENERATE_FALLBACK_KEY;
            try {
                stringRedisTemplate.opsForValue().set(fallbackKey, "1");
            } catch (Exception ignored) {
                // 修复标记写入失败不影响主流程
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
                        // 修复成功，返回合法 JSON（同时去除 thought 字段）
                        String raw = repairedNode.toString();
                        String cleanJson = removeThoughtFromJson(raw);
                        return new SendToLuna(raw, cleanJson, repairedNode.get(ModelHintConstant.REPLY).asText());
                    }
                }
            } catch (Exception ignored) {
                // 修复失败继续走最终兜底
            } finally {
                // 无论修复成功与否都清理标记，避免污染后续请求
                stringRedisTemplate.delete(fallbackKey);
            }

            // 二次修复仍失败，使用本地兜底模板
            String fallback = createFallbackJson();
            return new SendToLuna(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }

        // 正常路径：保留 raw 供日志审计，同时返回移除 thought 的安全 JSON
        String replyText = node.get(ModelHintConstant.REPLY).asText();
        String raw = node.toString();
        String cleanValid = removeThoughtFromJson(raw);
        return new SendToLuna(raw, cleanValid, replyText);
    }

    private JsonNode tryParseJsonNode(String text) {
        // 兼容 Markdown fenced code block 包裹的 JSON 输出
        if (text == null) return null;
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)```\\s*$", "").trim();
        }
        try {
            return mapper.readTree(cleaned);
        } catch (JsonProcessingException e) {
            // 解析失败返回 null，让上层统一走容错
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isValidReplyNode(JsonNode node) {
        // 当前最小契约：必须有 textual reply 字段
        return node != null && node.hasNonNull(ModelHintConstant.REPLY) && node.get(ModelHintConstant.REPLY).isTextual();
    }

    private String createFallbackJson() {
        // 统一兜底消息模板，保证输出结构稳定
        return "{\"thought\":\"系统降级，无法进行思考。\",\"emotion\":\"Solemn\",\"reply\":\"生成回复失败，请稍后重试。\"}";
    }

    private String extractReplyFromJsonSafe(String json) {
        // 从 JSON 中安全提取 reply（解析失败返回空串）
        JsonNode node = tryParseJsonNode(json);
        if (node != null && node.hasNonNull(ModelHintConstant.REPLY)) {
            return node.get(ModelHintConstant.REPLY).asText();
        }
        return "";
    }

    private String removeThoughtFromJson(String json) {
        // thought 属于内部推理，不对外返回
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
        // 识别工具链返回的异步状态
        JsonNode node = tryParseJsonNode(toolContext);
        return node != null && "pending".equalsIgnoreCase(node.path("status").asText(""));
    }

    private String buildPendingReply(String toolContext) {
        try {
            // 尽量从工具上下文透传 taskId/skillName，方便前端后续关联
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
            // pending 消息构造失败时也返回最简可用结构
            return "{\"emotion\":\"Soft\",\"reply\":\"Luna 已经开始处理任务，正在后台执行。\",\"status\":\"pending\"}";
        }
    }

    // 与旧代码兼容的小型返回载体：raw 用于日志，valid 用于返回，replyText 用于会话落盘
    private record SendToLuna(String raw, String valid, String replyText) {
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTaskKnowledgeSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return Collections.emptyList();
        }
        Object raw = contextPackage.getTaskContext().get("knowledge");
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }
        return ((List<Map<String, Object>>) list).stream()
                .map(item -> String.format("title: %s\ncontent: %s",
                        nullSafe(stringValue(item.get("title"))),
                        nullSafe(stringValue(item.get("chunk_text")))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTaskLongTermSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return Collections.emptyList();
        }
        List<String> snippets = new ArrayList<>();
        Object factsRaw = contextPackage.getTaskContext().get("task_facts");
        if (factsRaw instanceof List<?> facts) {
            snippets.addAll(((List<Map<String, Object>>) facts).stream()
                    .map(item -> String.format("task_fact: %s=%s",
                            nullSafe(stringValue(item.get("fact_key"))),
                            nullSafe(stringValue(item.get("fact_value_text")))))
                    .toList());
        }
        Object episodesRaw = contextPackage.getTaskContext().get("task_episodes");
        if (episodesRaw instanceof List<?> episodes) {
            snippets.addAll(((List<Map<String, Object>>) episodes).stream()
                    .map(item -> String.format("task_episode: %s | %s",
                            nullSafe(stringValue(item.get("episode_type"))),
                            nullSafe(stringValue(item.get("trajectory_summary")))))
                    .toList());
        }
        return snippets;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRuntimeMessageSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRuntime() == null) {
            return Collections.emptyList();
        }
        Object raw = contextPackage.getRuntime().get("recent_messages");
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }
        return ((List<Map<String, Object>>) list).stream()
                .map(item -> String.format("%s: %s",
                        nullSafe(stringValue(item.get("role"))),
                        nullSafe(stringValue(item.get("content_text")))))
                .toList();
    }

    private List<String> mergePreferenceSnippets(List<String> base, List<String> extra) {
        if (extra == null || extra.isEmpty()) {
            return base;
        }
        List<String> merged = new ArrayList<>(base == null ? Collections.emptyList() : base);
        merged.addAll(extra);
        return merged;
    }

    private List<String> mergeMemorySnippets(List<String> base, List<String> extra) {
        if (extra == null || extra.isEmpty()) {
            return base;
        }
        List<String> merged = new ArrayList<>(base == null ? Collections.emptyList() : base);
        merged.addAll(extra);
        return merged;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> toKnowledgeSnippets(RetrievalResponse response) {
        // knowledge evidence -> prompt 片段
        return getEvidences(response, RetrievalSource.KNOWLEDGE).stream()
                .map(evidence -> String.format("标题: %s\n内容: %s", nullSafe(evidence.getTitle()), nullSafe(evidence.getContent())))
                .toList();
    }

    private List<String> toPreferenceSnippets(RetrievalResponse response) {
        // preference evidence -> prompt 片段
        return getEvidences(response, RetrievalSource.PREFERENCE).stream()
                .map(evidence -> String.format("偏好键: %s, 偏好值: %s, 描述: %s",
                        nullSafe((String) evidence.getMetadata().get("pref_key")),
                        nullSafe((String) evidence.getMetadata().get("pref_value")),
                        nullSafe(evidence.getContent())))
                .toList();
    }

    private List<String> toMemorySnippets(RetrievalResponse response) {
        // memory evidence -> prompt 片段
        return getEvidences(response, RetrievalSource.MEMORY).stream()
                .map(evidence -> String.format("会话: %s, 类型: %s, 内容: %s",
                        nullSafe((String) evidence.getMetadata().get("session_id")),
                        nullSafe((String) evidence.getMetadata().get("memory_type")),
                        nullSafe(evidence.getContent())))
                .toList();
    }

    private List<Evidence> getEvidences(RetrievalResponse response, RetrievalSource source) {
        // 空保护，避免上层频繁判空
        if (response == null || response.getEvidences() == null) {
            return Collections.emptyList();
        }
        return response.getEvidences().getOrDefault(source, Collections.emptyList());
    }

    private String nullSafe(String value) {
        // prompt 拼接时避免 null 直接输出
        return value == null ? "" : value;
    }
}
