package org.yilena.runa.service.impl;

import cn.hutool.core.date.DateTime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.yilena.runa.enums.EmotionEnum;
import org.yilena.runa.prompt.PromptAssembler;
import org.yilena.runa.prompt.PromptTemplates;
import org.yilena.runa.service.ChatService;
import org.yilena.runa.service.SessionService;
import org.yilena.runa.utils.ServiceCommunicateUtil;

import javax.swing.text.DateFormatter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {
    private final OllamaClient ollamaClient;
    private final PromptAssembler promptAssembler;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SessionService sessionService;
    private final StringRedisTemplate stringRedisTemplate;

    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    @Override
    public ResponseEntity<String> chat(ChatRequest chatRequest){
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
        if (input.isEmpty()){
            return ResponseEntity.badRequest().body("用户输入为空");
        }

        // 将当前输入加入用户会话上下文当中
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.USER, input));

        // 获取上下文最近N条信息
        List<ChatMessage> recent = sessionService.getRecentMessages(keyPrefix, 20);

        // 提取上下文信息
        List<String> memorySnippets = recent.stream()
                .map(m -> m.getRole().name() + ": " + m.getContent())
                .collect(Collectors.toList());

        // 判断是否需要压缩
        if(ServiceCommunicateUtil.getSymbol(SymbolConstant.CONTEXT_SUMMARY_FLAG) == 1){
            log.info("开始进行上下文压缩");
            // 恢复标识
            ServiceCommunicateUtil.removeSymbol(SymbolConstant.CONTEXT_SUMMARY_FLAG);
            // 开启一条异步线程
            Thread.ofVirtual().start(() -> {
                // 获取压缩提示词
                String prompt = promptAssembler.buildSummaryPrompt(memorySnippets);
                // 发送给Luna
                SendToLuna result = getSendToLuna(prompt, keyPrefix);
                // 存储
                sessionService.replaceHistoryWithSummary(keyPrefix, result.replyText());
                log.info("上下文压缩完成");
            });
        }

        // 组装提示词
        String prompt = promptAssembler.assemble(memorySnippets, ModelHintConstant.UNSPECIFIED, ModelHintConstant.UNSPECIFIED, input);

        // 发送请求
        SendToLuna result = getSendToLuna(prompt, keyPrefix);
        log.info("模型输出：{}", result.valid());

        // 将模型输出加入上下文当中
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.ASSISTANT, result.replyText()));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result.valid());
    }

    private SendToLuna getSendToLuna(String prompt, String keyPrefix) {
        // 发送请求到大模型端
        String raw = null;
        try {
            raw = ollamaClient.generateSync(prompt);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        // 判断模型返回结果是否合法
        String valid = validateOrRepair(raw, prompt, keyPrefix);

        // 解析为JSON
        JsonNode node = null;
        try {
            node = mapper.readTree(valid);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        String replyText = node.get(ModelHintConstant.REPLY).asText();
        SendToLuna result = new SendToLuna(valid, replyText);
        return result;
    }

    private record SendToLuna(String valid, String replyText) {
    }

    private String validateOrRepair(String raw, String originalContext, String sessionId) {
        String key = String.format(RedisKeyConstant.GENERATE_FALLBACK_KEY, sessionId);

        // 包装成函数，用于标准化和校验JSON节点
        Function<JsonNode, String> normalizeAndValidate = (JsonNode candidate) -> {
            if (candidate == null || !candidate.isObject()) {
                throw new IllegalArgumentException("候选节点不是对象类型");
            }
            ObjectNode obj = (ObjectNode) candidate;

            // emotion字段检查
            if (!obj.has(ModelHintConstant.EMOTION) || obj.get(ModelHintConstant.EMOTION).isNull()) {
                throw new IllegalArgumentException("缺少emotion字段");
            }
            String emotion = obj.get(ModelHintConstant.EMOTION).asText().trim();
            if (!EmotionEnum.contains(emotion)) {
                throw new IllegalArgumentException("emotion值不合法: " + emotion);
            }
            // reply字段检查
            if (!obj.has(ModelHintConstant.REPLY) || obj.get(ModelHintConstant.REPLY).isNull()) {
                throw new IllegalArgumentException("缺少reply字段");
            }
            String reply = obj.get(ModelHintConstant.REPLY).asText().trim();
            if (reply.isEmpty()) {
                throw new IllegalArgumentException("reply内容为空");
            }
            // 如果超过最大长度 140，截断
            if (reply.length() > 140) {
                reply = reply.substring(0, 140);
            }
            // confidence 字段检查
            if (!obj.has(ModelHintConstant.CONFIDENCE) || obj.get(ModelHintConstant.CONFIDENCE).isNull()) {
                throw new IllegalArgumentException("缺少confidence字段");
            }
            double confidence;
            JsonNode confNode = obj.get(ModelHintConstant.CONFIDENCE);
            if (confNode.isNumber()) {
                confidence = confNode.asDouble();
            } else {
                try {
                    confidence = Double.parseDouble(confNode.asText());
                } catch (Exception ex) {
                    throw new IllegalArgumentException("confidence解析失败");
                }
            }
            if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence值不在[0,1]范围");
            }
            // 保留两位小数
            java.math.BigDecimal bd = new java.math.BigDecimal(Double.toString(confidence));
            bd = bd.setScale(2, java.math.RoundingMode.HALF_UP);
            double confRounded = bd.doubleValue();
            // 构建标准化后的对象
            ObjectNode normalized = mapper.createObjectNode();
            normalized.put(ModelHintConstant.EMOTION, emotion);
            normalized.put(ModelHintConstant.REPLY, reply);
            normalized.put(ModelHintConstant.CONFIDENCE, confRounded);
            try {
                return mapper.writeValueAsString(normalized);
            } catch (JsonProcessingException ex) {
                throw new RuntimeException("JSON序列化失败", ex);
            }
        };

        // 尝试从raw字符串中提取候选JSON节点
        Function<String, JsonNode> extractCandidate = (String text) -> {
            if (text == null) return null;
            text = text.trim();
            try {
                JsonNode root = mapper.readTree(text);
                // 情况 A：顶层已有 emotion/reply/confidence
                if (root.has(ModelHintConstant.EMOTION) && root.has(ModelHintConstant.REPLY) && root.has(ModelHintConstant.CONFIDENCE)) {
                    return root;
                }
                // 情况 B：Ollama 风格，response 字段可能是文本或对象
                if (root.has("response")) {
                    JsonNode resp = root.get("response");
                    if (resp.isTextual()) {
                        String inner = resp.asText().trim();
                        try {
                            JsonNode innerNode = mapper.readTree(inner);
                            if (innerNode.has(ModelHintConstant.EMOTION) && innerNode.has(ModelHintConstant.REPLY) && innerNode.has(ModelHintConstant.CONFIDENCE)) {
                                return innerNode;
                            } else {
                                // 内部 JSON 不完整
                                return null;
                            }
                        } catch (Exception ex) {
                            // response 是普通文本，无法解析为 JSON
                            return null;
                        }
                    } else if (resp.isObject()) {
                        if (resp.has(ModelHintConstant.EMOTION) && resp.has(ModelHintConstant.REPLY) && resp.has(ModelHintConstant.CONFIDENCE)) {
                            return resp;
                        } else {
                            return null;
                        }
                    } else {
                        return null;
                    }
                }
                // 其他情况，没有候选节点
                return null;
            } catch (IOException parseEx) {
                // raw 不是标准 JSON，尝试提取首尾大括号内容
                int first = text.indexOf('{');
                int last = text.lastIndexOf('}');
                if (first >= 0 && last > first) {
                    String sub = text.substring(first, last + 1);
                    try {
                        JsonNode root2 = mapper.readTree(sub);
                        if (root2.has(ModelHintConstant.EMOTION) && root2.has(ModelHintConstant.REPLY) && root2.has(ModelHintConstant.CONFIDENCE)) {
                            return root2;
                        }
                    } catch (Exception ex) {
                        // 忽略
                    }
                }
                return null;
            }
        };

        try {
            JsonNode candidate = extractCandidate.apply(raw);
            if (candidate != null) {
                String normalized = normalizeAndValidate.apply(candidate);
                // 删除降级标记
                stringRedisTemplate.delete(key);
                return normalized;
            } else {
                throw new IllegalArgumentException("raw中没有有效候选节点");
            }
        } catch (Exception e) {
            // 降级策略
            if (stringRedisTemplate.hasKey(key)) {
                stringRedisTemplate.delete(key);
                // 返回降级 fallback，置信度 1.00
                return "{\"emotion\":\"Solemn\",\"reply\":\"回复触发了降级策略。\",\"confidence\":1.00}";
            }
            // 设置降级标记
            stringRedisTemplate.opsForValue().set(key, "1");
            // 尝试修复
            String repairPrompt = PromptTemplates.REPAIR_PROMPT.formatted(originalContext);
            try {
                String repaired = ollamaClient.generateSync(repairPrompt);
                JsonNode repairedNode = extractCandidate.apply(repaired);
                if (repairedNode != null) {
                    String normalized = normalizeAndValidate.apply(repairedNode);
                    stringRedisTemplate.delete(key);
                    return normalized;
                } else {
                    stringRedisTemplate.delete(key);
                    return "{\"emotion\":\"Solemn\",\"reply\":\"回复触发了降级策略。\",\"confidence\":1.00}";
                }
            } catch (Exception ex) {
                stringRedisTemplate.delete(key);
                return "{\"emotion\":\"Solemn\",\"reply\":\"回复触发了降级策略。\",\"confidence\":1.00}";
            }
        }
    }
}
