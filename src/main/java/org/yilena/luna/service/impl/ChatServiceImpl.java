package org.yilena.luna.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.prompt.PromptAssembler;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.properties.GeminiProperty;
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

    // ObjectMapper 用于解析模型返回的 JSON 结果
    private final ObjectMapper mapper = new ObjectMapper();

    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    @Override
    @LunaLogRecord(module = LogModuleConstant.CHAT, action = LogActionConstant.CHAT, type = LogType.LUNA_OUTPUT, content = "用户对话交互")
    public ResponseEntity<Object> chat(ChatRequest chatRequest) {
        log.info("用户输入：{}", chatRequest.getUserInput());
        
        // 推送状态：开始思考
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING);
        
        // 获取当天日期
        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = dateFormatter.format(today);
        // 获取用户输入
        String input = Optional.ofNullable(chatRequest.getUserInput())
                .map(Object::toString)
                .orElse("")
                .trim();
        // 检查用户输入
        if (input.isEmpty()) {
            log.error("用户输入为空");
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return ResponseEntity.badRequest().body("用户输入为空");
        }

        // --- RAG 检索逻辑 ---
        List<String> knowledgeSnippets = null;
        try {
            // 推送状态：正在检索知识库
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_RETRIEVING, LunaStateConstant.VALUE_RETRIEVING);
            
            // 检索 Top 5 相关知识
            List<KnowledgeBase> kbs = knowledgeBaseService.searchKnowledge(input, 5);
            if (kbs != null && !kbs.isEmpty()) {
                knowledgeSnippets = kbs.stream()
                        .map(kb -> String.format("标题: %s\n内容: %s", kb.getTitle(), kb.getContent()))
                        .collect(Collectors.toList());
                log.info("RAG检索命中: {} 条", kbs.size());
            }
        } catch (Exception e) {
            log.error("RAG检索异常: {}", e.getMessage());
        }
        // -------------------

        // 恢复思考状态
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING_ORGANIZE);

        // 获取上下文最近N条信息（在写入用户消息前先获取，用于压缩判断）
        List<ChatMessage> recent = sessionService.getRecentMessages(keyPrefix, false);
        if (recent == null) {
            recent = Collections.emptyList();
        }

        // 提取上下文信息
        List<String> memorySnippets = recent.stream()
                .map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime())
                .collect(Collectors.toList());

        // 判断是否需要压缩（在写入用户消息前触发，避免新消息被压缩覆盖）
        if (ServiceCommunicateUtil.getSymbol(SymbolConstant.CONTEXT_SUMMARY_FLAG) == 1) {
            log.info("触发上下文压缩（异步），sessionKey={}。", keyPrefix);
            // 先移除标识，避免重复触发
            ServiceCommunicateUtil.removeSymbol(SymbolConstant.CONTEXT_SUMMARY_FLAG);
            // 将当前 memorySnippets 快照传入异步线程，避免数据竞争
            final List<String> snippetsSnapshot = List.copyOf(memorySnippets);
            Thread.ofVirtual().start(() -> {
                try {
                    String summaryPrompt = promptAssembler.buildSummaryPrompt(snippetsSnapshot);
                    if (summaryPrompt.isBlank()) {
                        log.info("上下文压缩：没有足够的 memory 片段可供压缩，sessionKey={}", keyPrefix);
                        return;
                    }
                    // 使用摘要模型进行上下文压缩
                    SendToLuna summaryResult = getSendToSummaryModel(summaryPrompt);
                    if (summaryResult.replyText() != null) {
                        sessionService.replaceHistoryWithSummary(keyPrefix, summaryResult.replyText());
                        log.info("上下文压缩完成，sessionKey={}", keyPrefix);
                    } else {
                        log.error("上下文压缩失败：模型返回为空，sessionKey={}", keyPrefix);
                    }
                } catch (Exception ex) {
                    log.error("上下文压缩线程发生异常，sessionKey={}, 错误信息={}", keyPrefix, ex.getMessage(), ex);
                }
            });

            // 压缩触发后，重新从 Redis 获取最新上下文，确保 prompt 不含过期数据
            List<ChatMessage> refreshed = sessionService.getRecentMessages(keyPrefix, false);
            if (refreshed == null) {
                refreshed = Collections.emptyList();
            }
            memorySnippets = refreshed.stream()
                    .map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime())
                    .collect(Collectors.toList());
            log.info("压缩触发后重新加载上下文，共 {} 条，sessionKey={}", memorySnippets.size(), keyPrefix);
        }

        // 将当前输入加入用户会话上下文当中（在压缩判断之后写入，避免被覆盖）
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.USER, input, LocalTime.now()));

        // --- 上下文裁剪 ---
        ContextPruner.ContextPayload payload = ContextPruner.ContextPayload.builder()
                .systemPrompt(PromptTemplates.SYSTEM_PROMPT)
                .userInput(input)
                .recentChatHistory(memorySnippets)
                .knowledgeBase(knowledgeSnippets)
                // TODO: 后续接入长期记忆、日程、偏好等模块时，在此处填充
                .userPreferences(Collections.emptyList())
                .scheduleReminders(Collections.emptyList())
                .longTermMemory(Collections.emptyList())
                .build();

        ContextPruner.ContextPayload pruned = ContextPruner.prune(payload);
        memorySnippets = pruned.getRecentChatHistory();
        knowledgeSnippets = pruned.getKnowledgeBase();
        // -----------------

        // 【新增】：调用 midModel (Router) 判断并执行工具
        String ragMerged = knowledgeSnippets != null ? String.join("\n", knowledgeSnippets) : "";
        String historyMerged = memorySnippets != null ? String.join("\n", memorySnippets) : "";
        String toolContext = llmClientUtil.executeToolsIfNecessary(input, ragMerged, historyMerged);

        // 组装最终提示词 (包含 RAG 和 Tool 结果)
        String prompt = promptAssembler.assembleFinalPrompt(memorySnippets, knowledgeSnippets, toolContext, input);

        // 发送请求给 bigModel (主脑)
        SendToLuna result = getSendToLuna(prompt);
        log.info("整理后模型输出：{}", result.valid());

        // 设置日志覆盖：记录包含 thought 的原始 JSON
        LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(result.raw());

        // 将模型输出加入上下文当中
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, result.replyText(), LocalTime.now()));
        
        // 推送状态：空闲
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
        
        // 返回 Object (JsonNode) 以解决 406 Not Acceptable 问题
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
                // 今日首次启动，尝试获取之前的上下文
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
        // 将开机命令加入上下文
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.STARTUP, "用户启动", LocalTime.now()));
        List<String> memorySnippets = recent.stream()
                .map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime())
                .toList();
        // 组装开机提示词
        String prompt = promptAssembler.assembleStartupPrompt(memorySnippets);
        // 发送
        SendToLuna result = getSendToLuna(prompt);
        log.info("整理后模型输出：{}", result.valid());

        // 设置日志覆盖：记录包含 thought 的原始 JSON
        LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(result.raw());

        // 将模型输出加入到上下文
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, result.replyText(), LocalTime.now()));
        
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
        
        // 返回 Object (JsonNode) 以解决 406 Not Acceptable 问题
        return ResponseEntity.ok(tryParseJsonNode(result.valid()));
    }

    @Override
    @LunaLogRecord(module = LogModuleConstant.SYSTEM, action = LogActionConstant.SHUTDOWN, type = LogType.SYSTEM_EVENT, content = "系统关闭")
    public void shutdown() {
        log.info("开始关机流程");
        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = dateFormatter.format(today);
        // 将关机命令加入上下文
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.SHUTDOWN, "用户关机", LocalTime.now()));
    }

    @Override
    public List<String> getHistoryDate(String yearMonth) {
        String cacheKeyPrefix = String.format(RedisKeyConstant.CONTEXT_KEY_PREFIX, yearMonth) + ":";
        // 构造
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
                // 去掉前缀，保留日期部分
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
     * 调用摘要模型
     */
    private SendToLuna getSendToSummaryModel(String prompt) {
        LlmRequest request = LlmRequest.builder()
                .modelType(ModelType.OPENAI_COMPATIBLE)
                .modelName(geminiProperty.getMid().getModelName())
                .messages(List.of(LlmMessage.user(prompt)))
                .build();

        LlmResponse response = llmClientUtil.generate(request);
        String text = response != null ? response.getContent() : null;

        // 校验摘要结果：为空或过短均视为无效
        if (text == null || text.isBlank() || text.length() < 10) {
            log.warn("调用Summary模型返回无效结果，text={}", text);
            return new SendToLuna("{\"emotion\":\"Solemn\",\"reply\":\"<error>生成摘要失败</error>\"}", "{\"emotion\":\"Solemn\",\"reply\":\"<error>生成摘要失败</error>\"}", "<error>生成摘要失败</error>");
        }
        return new SendToLuna(text, text, text);
    }

    /**
     * 调用主对话模型 (bigModel)，不再处理工具，只负责生成 JSON
     */
    private SendToLuna getSendToLuna(String prompt) {
        // 直接使用 llmClientUtil.generate 调用 bigModel
        LlmRequest request = LlmRequest.builder()
                .modelType(ModelType.OPENAI_COMPATIBLE)
                .modelName(geminiProperty.getBig().getModelName()) // 强制使用 bigModel
                .messages(List.of(LlmMessage.user(prompt)))
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
        // 如果解析失败或不包含reply字段，尝试用REPAIR_PROMPT修复
        if (!isValidReplyNode(node)) {
            log.warn("模型输出无法解析或不包含 reply 字段，尝试修复。原始输出：{}", valid);
            // 设置降级标记
            String fallbackKey = RedisKeyConstant.GENERATE_FALLBACK_KEY;
            try {
                stringRedisTemplate.opsForValue().set(fallbackKey, "1");
            } catch (Exception e) {
                log.error("设置降级标记失败：{}", e.getMessage());
            }

            try {
                // 获取修复Prompt
                String repairPrompt = PromptTemplates.REPAIR_PROMPT.formatted(valid);
                // 修复不需要工具，可以直接用 llmClientUtil.generate
                LlmRequest repairReq = LlmRequest.builder()
                        .modelType(ModelType.OPENAI_COMPATIBLE)
                        .modelName(geminiProperty.getBig().getModelName())
                        .messages(List.of(LlmMessage.user(repairPrompt)))
                        .build();

                LlmResponse repairRes = llmClientUtil.generate(repairReq);
                String repairedText = repairRes != null ? repairRes.getContent() : null;

                if (repairedText != null) {
                    JsonNode repairedNode = tryParseJsonNode(repairedText);
                    if (isValidReplyNode(repairedNode)) {
                        log.info("REPAIR_PROMPT 修复成功");
                        // 修复成功后，同样需要移除 thought 字段再返回给前端
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
            // 修复失败，返回降级JSON
            String fallback = createFallbackJson();
            log.error("模型输出最终不可用，返回降级内容：{}", fallback);
            return new SendToLuna(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }

        // 解析成功且包含 reply 字段
        String replyText = node.get(ModelHintConstant.REPLY).asText();
        String raw = node.toString();
        // 移除 thought 字段，只保留 emotion 和 reply 返回给前端
        String cleanValid = removeThoughtFromJson(raw);
        return new SendToLuna(raw, cleanValid, replyText);
    }

    private JsonNode tryParseJsonNode(String text) {
        if (text == null) return null;
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            // 使用 (?s) 開啟 DOTALL 模式，讓 . 能匹配換行符
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
        // 降级返回，包含 thought 字段以保持格式一致性
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
     * 从 JSON 字符串中移除 thought 字段，仅保留 emotion 和 reply
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
        // 如果处理失败，返回原 JSON（虽然包含 thought，但至少是合法的 JSON）
        return json;
    }

    private record SendToLuna(String raw, String valid, String replyText) {
    }
}
