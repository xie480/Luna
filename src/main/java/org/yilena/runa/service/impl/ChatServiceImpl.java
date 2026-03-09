package org.yilena.runa.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.yilena.runa.client.OllamaClient;
import org.yilena.runa.constants.ModelHintConstant;
import org.yilena.runa.constants.RedisKeyConstant;
import org.yilena.runa.constants.SymbolConstant;
import org.yilena.runa.entity.ChatMessage;
import org.yilena.runa.entity.ChatRequest;
import org.yilena.runa.prompt.PromptAssembler;
import org.yilena.runa.prompt.PromptTemplates;
import org.yilena.runa.properties.GeminiProperty;
import org.yilena.runa.service.ChatService;
import org.yilena.runa.service.SessionService;
import org.yilena.runa.utils.ServiceCommunicateUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private final OllamaClient ollamaClient;
    private final PromptAssembler promptAssembler;
    private final SessionService sessionService;
    private final StringRedisTemplate stringRedisTemplate;

    // 替换原有的 QwenProperty，改用 GeminiProperty 读取中转站配置（url、api、模型名称等）
    private final GeminiProperty geminiProperty;

    // ObjectMapper 用于序列化请求体与解析响应
    private final ObjectMapper mapper = new ObjectMapper();

    // 使用 JDK 内置 HttpClient 发送 HTTP 请求到中转站，声明为静态避免被 Lombok 注入
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    @Override
    public ResponseEntity<String> chat(ChatRequest chatRequest) {
        log.info("用户输入：{}", chatRequest.getUserInput());
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
            return ResponseEntity.badRequest().body("用户输入为空");
        }

        // 将当前输入加入用户会话上下文当中
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.USER, input, LocalTime.now()));

        // 获取上下文最近N条信息
        List<ChatMessage> recent = sessionService.getRecentMessages(keyPrefix, false);
        if (recent == null) {
            recent = Collections.emptyList();
        }

        // 提取上下文信息
        List<String> memorySnippets = recent.stream()
                .map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime())
                .collect(Collectors.toList());

        // 判断是否需要压缩
        if (ServiceCommunicateUtil.getSymbol(SymbolConstant.CONTEXT_SUMMARY_FLAG) == 1) {
            log.info("触发上下文压缩（异步），sessionKey={}。", keyPrefix);
            // 先移除标识，避免重复触发
            ServiceCommunicateUtil.removeSymbol(SymbolConstant.CONTEXT_SUMMARY_FLAG);
            Thread.ofVirtual().start(() -> {
                try {
                    String summaryPrompt = promptAssembler.buildSummaryPrompt(memorySnippets);
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
        }

        // 组装提示词
        String prompt = promptAssembler.assemble(memorySnippets, input);

        // 发送请求
        SendToLuna result = getSendToLuna(prompt);
        log.info("整理后模型输出：{}", result.valid());

        // 将模型输出加入上下文当中
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, result.replyText(), LocalTime.now()));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result.valid());
    }

    @Override
    public ResponseEntity<String> startup() {
        log.info("开始启动流程");
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
        // 将模型输出加入到上下文
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, result.replyText(), LocalTime.now()));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result.valid());
    }

    @Override
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
     * 通过中转站（OpenAI 兼容接口）调用指定模型。
     *
     * @param modelName 模型名称，由 GeminiProperty 提供（chatModelName 或 summaryModelName）
     * @param prompt    发送给模型的提示词
     * @return 模型返回的文本内容，失败时返回 null
     */
    private String callRelay(String modelName, String prompt) {
        try {
            // 构造符合 OpenAI 兼容规范的请求体（messages 数组 + model 字段）
            String requestBody = mapper.writeValueAsString(Map.of(
                    "model", modelName,
                    "messages", List.of(
                            Map.of("role", "user", "content", prompt)
                    )
            ));

            // 构造 HTTP POST 请求，携带中转站 URL 及 Bearer Token 形式的 API 密钥
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(geminiProperty.getUrl()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + geminiProperty.getApi())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // 非200状态码视为调用失败
            if (response.statusCode() != 200) {
                log.error("中转站返回非200状态码，model={}，statusCode={}，body={}", modelName, response.statusCode(), response.body());
                return null;
            }

            // 解析 OpenAI 兼容格式的响应，提取 choices[0].message.content
            JsonNode root = mapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                log.error("中转站响应中未找到 choices[0].message.content，model={}，body={}", modelName, response.body());
                return null;
            }
            return content.asText();

        } catch (Exception e) {
            log.error("调用中转站模型失败，model={}，错误信息={}", modelName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 调用摘要模型（使用 GeminiProperty 中配置的 summaryModelName）
     */
    private SendToLuna getSendToSummaryModel(String prompt) {
        String text = callRelay(geminiProperty.getSummaryModelName(), prompt);
        if (text == null) {
            log.warn("调用Summary模型返回空结果");
            return new SendToLuna("{\"emotion\":\"Solemn\",\"reply\":\"<error>生成摘要失败</error>\"}", "<error>生成摘要失败</error>");
        }
        return new SendToLuna(text, text);
    }

    /**
     * 调用主对话模型（使用 GeminiProperty 中配置的 chatModelName），并处理 JSON 校验与修复逻辑
     */
    private SendToLuna getSendToLuna(String prompt) {
        String valid = callRelay(geminiProperty.getChatModelName(), prompt);
        if (valid == null) {
            log.error("主模型调用失败，返回降级回复");
            String fallback = createFallbackJson();
            return new SendToLuna(fallback, extractReplyFromJsonSafe(fallback));
        }

        JsonNode node = tryParseJsonNode(valid);
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
                // 使用主模型尝试修复不合规的输出
                String repairedText = callRelay(geminiProperty.getChatModelName(), repairPrompt);
                if (repairedText != null) {
                    JsonNode repairedNode = tryParseJsonNode(repairedText);
                    if (isValidReplyNode(repairedNode)) {
                        log.info("REPAIR_PROMPT 修复成功");
                        return new SendToLuna(repairedText, repairedNode.get(ModelHintConstant.REPLY).asText());
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
            return new SendToLuna(fallback, extractReplyFromJsonSafe(fallback));
        }

        // 解析成功且包含 reply 字段
        String replyText = node.get(ModelHintConstant.REPLY).asText();
        return new SendToLuna(valid, replyText);
    }

    private JsonNode tryParseJsonNode(String text) {
        if (text == null) return null;
        try {
            return mapper.readTree(text);
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
        // 降级返回，仅包含 emotion + reply，便于后端解析
        return "{\"emotion\":\"Solemn\",\"reply\":\"生成回复失败，请稍后重试。\"}";
    }

    private String extractReplyFromJsonSafe(String json) {
        JsonNode node = tryParseJsonNode(json);
        if (node != null && node.hasNonNull(ModelHintConstant.REPLY)) {
            return node.get(ModelHintConstant.REPLY).asText();
        }
        return "";
    }

    private record SendToLuna(String valid, String replyText) {
    }
}
