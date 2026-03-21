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
    /**
     * Prompt 组装器：负责拼接 System/Memory/RAG/Runtime 等区块
     */
    private final PromptAssembler promptAssembler;

    /**
     * 会话服务：负责 Redis 中上下文历史管理
     */
    private final SessionService sessionService;

    /**
     * Redis 访问模板（字符串）
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 模型配置（small/mid/big/flash）
     */
    private final GeminiProperty geminiProperty;

    /**
     * LLM 客户端工具（聊天调用、embedding、rerank）
     */
    private final LlmClientUtil llmClientUtil;

    /**
     * 知识库服务（向量检索）
     */
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * SSE 状态推送器（前端状态条）
     */
    private final LunaStatusPublisher statusPublisher;

    /**
     * Agent 工具调用编排器（Tool/Skill 决策与执行）
     */
    private final AgentService agentService;

    /**
     * RocketMQ 模板（用于发送异步摘要任务）
     */
    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 用户偏好向量检索 mapper
     */
    private final UserPreferenceMapper userPreferenceMapper;

    /**
     * 长期记忆向量检索 mapper
     */
    private final MemoryMapper memoryMapper;

    /**
     * 当前类内部 JSON 工具
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 会话日期 key 规则：yyyy:MM:dd
     */
    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    /**
     * RAG 初筛数量（用于 rerank 前召回）
     */
    private static final int RAG_TOP_K_FETCH = 12;

    /**
     * RAG 最终保留数量（rerank 后）
     */
    private static final int RAG_TOP_K_FINAL = 5;

    /**
     * 并行检索分支超时（毫秒）
     */
    private static final long RAG_TIMEOUT_MS = 2500;

    @Override
    @LunaLogRecord(module = LogModuleConstant.CHAT, action = LogActionConstant.CHAT, type = LogType.LUNA_OUTPUT, content = "用户对话交互")
    public ResponseEntity<Object> chat(ChatRequest chatRequest) {
        // -------------------- 0. 入口处理 --------------------
        String rawInput = chatRequest != null ? chatRequest.getUserInput() : null;
        log.info("收到 chat 请求，rawInputLength={}", rawInput != null ? rawInput.length() : 0);

        // 首次状态推送：进入“思考中”
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING);

        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = dateFormatter.format(today);

        // 标准化用户输入（trim + null 安全）
        String input = Optional.ofNullable(rawInput)
                .map(Object::toString)
                .orElse("")
                .trim();

        if (input.isEmpty()) {
            log.warn("chat 拒绝：用户输入为空，keyPrefix={}", keyPrefix);
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return ResponseEntity.badRequest().body("用户输入为空");
        }
        log.info("chat 输入规范化完成，keyPrefix={}, inputLength={}", keyPrefix, input.length());

        // -------------------- 1. 初始化 RAG 容器 --------------------
        List<String> knowledgeSnippets = Collections.emptyList();
        List<String> preferenceSnippets = Collections.emptyList();
        List<String> longTermMemorySnippets = Collections.emptyList();

        // -------------------- 2. 并行 RAG 检索 --------------------
        // 目标：降低整体等待时延（embedding + kb + preference + memory）
        try (ExecutorService vtp = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rag-vt-", 1).factory())) {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_RETRIEVING, LunaStateConstant.VALUE_RETRIEVING);
            log.info("开始并行 RAG 检索，timeoutMs={}, fetchTopK={}, finalTopK={}", RAG_TIMEOUT_MS, RAG_TOP_K_FETCH, RAG_TOP_K_FINAL);

            // 2.1 查询向量化（其他分支可复用）
            CompletableFuture<String> queryVectorFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    log.debug("RAG 向量化开始");
                    String vector = llmClientUtil.getEmbedding(input);
                    log.debug("RAG 向量化完成，vectorEmpty={}", vector == null || vector.isBlank());
                    return vector;
                } catch (Exception e) {
                    log.error("RAG 向量化异常: {}", e.getMessage(), e);
                    return null;
                }
            }, vtp).completeOnTimeout(null, RAG_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // 2.2 知识库检索 + rerank
            CompletableFuture<List<KnowledgeBase>> kbFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    List<KnowledgeBase> kbs = knowledgeBaseService.searchKnowledge(input, RAG_TOP_K_FETCH);
                    if (kbs == null || kbs.isEmpty()) {
                        log.debug("知识库检索无命中");
                        return Collections.<KnowledgeBase>emptyList();
                    }

                    List<String> docs = kbs.stream()
                            .map(kb -> String.format("标题: %s\n内容: %s", kb.getTitle(), kb.getContent()))
                            .toList();

                    if (docs.size() <= RAG_TOP_K_FINAL) {
                        log.info("知识库命中较少({})，跳过 rerank", docs.size());
                        return kbs.stream().limit(RAG_TOP_K_FINAL).toList();
                    }

                    List<Double> scores = llmClientUtil.rerank(input, docs);
                    List<KnowledgeBase> reranked = llmClientUtil.rerankResources(kbs, scores, RAG_TOP_K_FINAL);
                    log.info("知识库检索命中={}，rerank后={}", kbs.size(), reranked.size());
                    return reranked;
                } catch (Exception e) {
                    log.error("知识库检索异常: {}", e.getMessage(), e);
                    return Collections.<KnowledgeBase>emptyList();
                }
            }, vtp).completeOnTimeout(Collections.<KnowledgeBase>emptyList(), RAG_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // 2.3 用户偏好检索 + rerank（依赖 queryVector）
            CompletableFuture<List<UserPreference>> preferenceFuture = queryVectorFuture.thenApplyAsync(queryVector -> {
                try {
                    if (queryVector == null || queryVector.isBlank()) {
                        log.debug("用户偏好检索跳过：queryVector 为空");
                        return Collections.<UserPreference>emptyList();
                    }

                    List<UserPreference> preferences = userPreferenceMapper.searchByVector(queryVector, RAG_TOP_K_FETCH);
                    if (preferences == null || preferences.isEmpty()) {
                        log.debug("用户偏好检索无命中");
                        return Collections.<UserPreference>emptyList();
                    }

                    List<String> docs = preferences.stream()
                            .map(p -> String.format("键: %s\n值: %s\n描述: %s", p.getPrefKey(), p.getPrefValue(), p.getDescription()))
                            .toList();

                    if (docs.size() <= RAG_TOP_K_FINAL) {
                        log.info("用户偏好命中较少({})，跳过 rerank", docs.size());
                        return preferences.stream().limit(RAG_TOP_K_FINAL).toList();
                    }

                    List<Double> scores = llmClientUtil.rerank(input, docs);
                    List<UserPreference> reranked = llmClientUtil.rerankResources(preferences, scores, RAG_TOP_K_FINAL);
                    log.info("用户偏好检索命中={}，rerank后={}", preferences.size(), reranked.size());
                    return reranked;
                } catch (Exception e) {
                    log.error("用户偏好检索异常: {}", e.getMessage(), e);
                    return Collections.<UserPreference>emptyList();
                }
            }, vtp).completeOnTimeout(Collections.<UserPreference>emptyList(), RAG_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // 2.4 长期记忆检索 + rerank（依赖 queryVector）
            CompletableFuture<List<Memory>> memoryFuture = queryVectorFuture.thenApplyAsync(queryVector -> {
                try {
                    if (queryVector == null || queryVector.isBlank()) {
                        log.debug("长期记忆检索跳过：queryVector 为空");
                        return Collections.<Memory>emptyList();
                    }

                    List<Memory> memories = memoryMapper.searchByVector(queryVector, RAG_TOP_K_FETCH);
                    if (memories == null || memories.isEmpty()) {
                        log.debug("长期记忆检索无命中");
                        return Collections.<Memory>emptyList();
                    }

                    List<String> docs = memories.stream()
                            .map(m -> String.format("会话: %s\n类型: %s\n内容: %s", m.getSessionId(), m.getMemoryType(), m.getContent()))
                            .toList();

                    if (docs.size() <= RAG_TOP_K_FINAL) {
                        log.info("长期记忆命中较少({})，跳过 rerank", docs.size());
                        return memories.stream().limit(RAG_TOP_K_FINAL).toList();
                    }

                    List<Double> scores = llmClientUtil.rerank(input, docs);
                    List<Memory> reranked = llmClientUtil.rerankResources(memories, scores, RAG_TOP_K_FINAL);
                    log.info("长期记忆检索命中={}，rerank后={}", memories.size(), reranked.size());
                    return reranked;
                } catch (Exception e) {
                    log.error("长期记忆检索异常: {}", e.getMessage(), e);
                    return Collections.<Memory>emptyList();
                }
            }, vtp).completeOnTimeout(Collections.<Memory>emptyList(), RAG_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // 2.5 汇总并格式化 snippets
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

            log.info("并行 RAG 完成：knowledge={}, preference={}, memory={}",
                    knowledgeSnippets.size(), preferenceSnippets.size(), longTermMemorySnippets.size());
        } catch (Exception e) {
            // RAG 失败不阻断主流程
            log.error("并行 RAG 总流程异常: {}", e.getMessage(), e);
        }

        // 进入组织语言状态
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING_ORGANIZE);

        // -------------------- 3. 读取近期会话 --------------------
        List<ChatMessage> recent = sessionService.getRecentMessages(keyPrefix, false);
        if (recent == null) {
            recent = Collections.emptyList();
        }
        log.info("加载近期会话完成，条数={}", recent.size());

        List<String> memorySnippets = recent.stream()
                .map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime())
                .collect(Collectors.toList());

        // -------------------- 4. 上下文压缩触发（异步 MQ） --------------------
        if (ServiceCommunicateUtil.getSymbol(SymbolConstant.CONTEXT_SUMMARY_FLAG) == 1) {
            log.info("检测到上下文压缩标记，开始投递 MQ，sessionKey={}", keyPrefix);
            ServiceCommunicateUtil.removeSymbol(SymbolConstant.CONTEXT_SUMMARY_FLAG);

            SummaryMessage msg = SummaryMessage.builder()
                    .sessionKey(keyPrefix)
                    .memorySnippets(List.copyOf(memorySnippets))
                    .build();
            rocketMQTemplate.convertAndSend(RocketMqConstant.TOPIC_SUMMARY, msg);

            // 重新拉取一次历史（给已压缩场景预留一致性）
            List<ChatMessage> refreshed = sessionService.getRecentMessages(keyPrefix, false);
            if (refreshed == null) {
                refreshed = Collections.emptyList();
            }
            memorySnippets = refreshed.stream()
                    .map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime())
                    .collect(Collectors.toList());
            log.info("上下文压缩触发后重载会话，条数={}", memorySnippets.size());
        }

        // -------------------- 5. 先写入当前用户消息 --------------------
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.USER, input, LocalTime.now()));
        log.debug("已写入用户消息到会话，sessionKey={}", keyPrefix);

        // -------------------- 6. Prompt 裁剪前数据封装 --------------------
        ContextPruner.ContextPayload payload = ContextPruner.ContextPayload.builder()
                .systemPrompt(PromptTemplates.SYSTEM_PROMPT)
                .userInput(input)
                .recentChatHistory(memorySnippets)
                .knowledgeBase(knowledgeSnippets)
                .userPreferences(preferenceSnippets)
                .scheduleReminders(Collections.emptyList())
                .longTermMemory(longTermMemorySnippets)
                .build();

        // 进行上下文裁剪，避免超长 prompt
        ContextPruner.ContextPayload pruned = ContextPruner.prune(payload);
        memorySnippets = pruned.getRecentChatHistory();
        knowledgeSnippets = pruned.getKnowledgeBase();
        log.info("上下文裁剪完成：recentChat={}, knowledge={}",
                memorySnippets != null ? memorySnippets.size() : 0,
                knowledgeSnippets != null ? knowledgeSnippets.size() : 0);

        // -------------------- 7. Agent 工具调用 --------------------
        String toolContext = agentService.processToolCalling(keyPrefix, input);
        log.info("Agent 工具调用完成，toolContextEmpty={}", toolContext == null || toolContext.isBlank());

        // -------------------- 8. 组装最终 Prompt --------------------
        String prompt = promptAssembler.assembleFinalPrompt(memorySnippets, knowledgeSnippets, toolContext, input);
        log.info("Prompt 组装完成，length={}", prompt != null ? prompt.length() : 0);

        // -------------------- 9. 调用模型（含修复兜底） --------------------
        SendToLuna result = getSendToLuna(prompt, input);
        log.info("模型输出整理完成，validLength={}, replyLength={}",
                result.valid() != null ? result.valid().length() : 0,
                result.replyText() != null ? result.replyText().length() : 0);

        // AOP 覆盖 responseData：保存完整原始输出（包含 thought）
        LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(result.raw());

        // -------------------- 10. 写入 Luna 回复 --------------------
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, result.replyText(), LocalTime.now()));
        log.debug("已写入 Luna 回复到会话，sessionKey={}", keyPrefix);

        // 恢复 idle 状态
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);

        // 对外返回时使用去除 thought 的版本
        return ResponseEntity.ok(tryParseJsonNode(result.valid()));
    }

    @Override
    @LunaLogRecord(module = LogModuleConstant.SYSTEM, action = LogActionConstant.STARTUP, type = LogType.SYSTEM_EVENT, content = "系统启动")
    public ResponseEntity<Object> startup() {
        log.info("收到 startup 请求");
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_STARTING, LunaStateConstant.VALUE_STARTING);

        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = dateFormatter.format(today);
        List<ChatMessage> recent = null;

        // 1) 优先读取今日上下文
        String redisKey = String.format(RedisKeyConstant.CONTEXT_KEY_PREFIX, keyPrefix);
        if (!stringRedisTemplate.hasKey(redisKey)) {
            // 2) 今日无上下文，向前最多回溯 30 天
            int index = 1;
            while (index <= 30 && (recent == null || recent.isEmpty())) {
                recent = sessionService.getRecentMessages(dateFormatter.format(today.minusDays(index++)), true);
                if (recent == null) {
                    recent = Collections.emptyList();
                }
            }
            log.info("startup 回溯历史完成，命中条数={}", recent.size());
        } else {
            recent = sessionService.getRecentMessages(keyPrefix, false);
            if (recent == null) {
                recent = Collections.emptyList();
            }
            log.info("startup 读取今日历史完成，条数={}", recent.size());
        }

        // 3) 记录 startup 事件到会话
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.STARTUP, "用户启动", LocalTime.now()));

        // 4) 组装启动 prompt
        List<String> memorySnippets = recent.stream()
                .map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime())
                .toList();
        String prompt = promptAssembler.assembleStartupPrompt(memorySnippets);
        log.info("startup prompt 组装完成，length={}", prompt.length());

        // 5) 调用模型 + 兜底修复
        SendToLuna result = getSendToLuna(prompt, "startup");
        log.info("startup 模型输出完成，replyLength={}", result.replyText() != null ? result.replyText().length() : 0);

        // AOP 覆盖 responseData：保存完整原始输出（包含 thought）
        LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(result.raw());

        // 6) 写入 Luna 启动回复
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, result.replyText(), LocalTime.now()));

        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);

        return ResponseEntity.ok(tryParseJsonNode(result.valid()));
    }

    @Override
    @LunaLogRecord(module = LogModuleConstant.SYSTEM, action = LogActionConstant.SHUTDOWN, type = LogType.SYSTEM_EVENT, content = "系统关闭")
    public void shutdown() {
        log.info("收到 shutdown 请求");
        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = dateFormatter.format(today);
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.SHUTDOWN, "用户关机", LocalTime.now()));
        log.info("shutdown 事件已写入会话，sessionKey={}", keyPrefix);
    }

    @Override
    public List<String> getHistoryDate(String yearMonth) {
        log.info("查询历史日期，yearMonth={}", yearMonth);

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

        log.info("历史日期查询完成，yearMonth={}, count={}", yearMonth, result.size());
        return result;
    }

    @Override
    public List<String> getHistory(String yearMonthDay) {
        log.info("查询历史详情，yearMonthDay={}", yearMonthDay);
        List<ChatMessage> chats = sessionService.getRecentMessages(yearMonthDay, true);
        List<String> out = chats.stream()
                .map(m -> m.getRole().name() + ":" + m.getContent() + ":" + m.getTime())
                .toList();
        log.info("历史详情查询完成，yearMonthDay={}, count={}", yearMonthDay, out.size());
        return out;
    }

    /**
     * 调用主模型并做结构化修复兜底
     *
     * @param prompt            最终完整 Prompt
     * @param originalUserInput 原始用户输入（修复失败时作为种子）
     */
    private SendToLuna getSendToLuna(String prompt, String originalUserInput) {
        // 1) 构建主请求
        LlmRequest request = LlmRequest.builder()
                .modelType(ModelType.OPENAI_COMPATIBLE)
                .modelName(geminiProperty.getBig().getModelName())
                .messages(List.of(LlmMessage.user(prompt)))
                .enablePromptInjectionCheck(true)
                .build();

        // 2) 调用主模型
        LlmResponse response = llmClientUtil.generate(request);
        String valid = response != null ? response.getContent() : null;

        // 3) 主模型不可用 -> 直接降级
        if (valid == null) {
            log.error("主模型返回为空，进入降级回复");
            String fallback = createFallbackJson();
            return new SendToLuna(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }

        JsonNode node = tryParseJsonNode(valid);
        log.debug("主模型原始输出={}", node != null ? node.toString() : valid);

        // 4) 校验必须包含 reply 字段
        if (!isValidReplyNode(node)) {
            log.warn("主模型输出不合规（缺失/非法 reply），触发修复流程");
            String fallbackKey = RedisKeyConstant.GENERATE_FALLBACK_KEY;

            try {
                // 打标：当前进入修复/降级路径
                stringRedisTemplate.opsForValue().set(fallbackKey, "1");
            } catch (Exception e) {
                log.error("写入降级标记失败：{}", e.getMessage(), e);
            }

            try {
                // 5) 组装修复 prompt
                String repairSeed = (originalUserInput != null && !originalUserInput.isBlank()) ? originalUserInput : valid;
                String repairPrompt = PromptTemplates.REPAIR_PROMPT.formatted(repairSeed);

                LlmRequest repairReq = LlmRequest.builder()
                        .modelType(ModelType.OPENAI_COMPATIBLE)
                        .modelName(geminiProperty.getBig().getModelName())
                        .messages(List.of(LlmMessage.user(repairPrompt)))
                        .enablePromptInjectionCheck(false)
                        .build();

                // 6) 调用修复模型
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
                        log.error("REPAIR_PROMPT 返回仍不合规，repairedText={}", repairedText);
                    }
                } else {
                    log.error("REPAIR_PROMPT 返回为空");
                }
            } catch (Exception ex) {
                log.error("修复流程异常：{}", ex.getMessage(), ex);
            } finally {
                // 无论如何都清理降级标记
                stringRedisTemplate.delete(fallbackKey);
            }

            // 7) 修复失败 -> 最终降级
            String fallback = createFallbackJson();
            log.error("修复失败，返回最终降级内容={}", fallback);
            return new SendToLuna(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }

        // 8) 主模型输出合规 -> 返回
        String replyText = node.get(ModelHintConstant.REPLY).asText();
        String raw = node.toString();
        String cleanValid = removeThoughtFromJson(raw);
        return new SendToLuna(raw, cleanValid, replyText);
    }

    /**
     * 尝试解析 JSON（兼容 ```json code block```）
     */
    private JsonNode tryParseJsonNode(String text) {
        if (text == null) return null;

        String cleaned = text.trim();

        // 去掉 markdown 包裹
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z]*\\s*", "")
                    .replaceAll("(?s)```\\s*$", "")
                    .trim();
        }

        try {
            return mapper.readTree(cleaned);
        } catch (JsonProcessingException e) {
            log.warn("JSON 解析失败：{}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("JSON 解析异常：{}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 校验输出节点是否包含可用 reply
     */
    private boolean isValidReplyNode(JsonNode node) {
        return node != null && node.hasNonNull(ModelHintConstant.REPLY) && node.get(ModelHintConstant.REPLY).isTextual();
    }

    /**
     * 固定降级 JSON
     */
    private String createFallbackJson() {
        return "{\"thought\":\"系统降级，无法进行思考。\",\"emotion\":\"Solemn\",\"reply\":\"生成回复失败，请稍后重试。\"}";
    }

    /**
     * 安全提取 reply 字段（解析失败返回空字符串）
     */
    private String extractReplyFromJsonSafe(String json) {
        JsonNode node = tryParseJsonNode(json);
        if (node != null && node.hasNonNull(ModelHintConstant.REPLY)) {
            return node.get(ModelHintConstant.REPLY).asText();
        }
        return "";
    }

    /**
     * 对外返回前移除 thought，避免暴露内部推理
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
            log.warn("移除 thought 失败：{}", e.getMessage(), e);
        }
        return json;
    }

    /**
     * 内部返回对象：
     * raw   = 原始完整输出（含 thought）
     * valid = 对外可返回输出（去 thought）
     * replyText = 纯回复文本（写入会话）
     */
    private record SendToLuna(String raw, String valid, String replyText) {
    }
}
