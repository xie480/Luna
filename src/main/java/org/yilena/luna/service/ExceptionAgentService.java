package org.yilena.luna.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.exception.LunaExceptionContext;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.List;

/**
 * 异常分析 Agent 服务
 * 负责构建 Prompt 并调用 LLM 进行决策
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExceptionAgentService {

    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final ObjectMapper objectMapper;

    private static final String EXCEPTION_ANALYSIS_PROMPT = """
            你是 AI Agent「Luna」，可以通过 MCP Tools 操作系统。
            
            系统刚刚发生了一次异常，请判断是否可以通过调用 Tool 修复。
            
            异常信息：
            %s
            
            异常类型：
            %s
            
            接口路径：
            %s
            
            请求参数：
            %s
            
            用户输入：
            %s
            
            你的任务：
            
            步骤1
            判断该异常是否可以通过 MCP Tool 修复。
            
            步骤2
            如果可以修复，请返回：
            {
             "canFix": true,
             "tool": "tool_name",
             "params": {}
            }
            
            步骤3
            如果无法修复，请返回：
            {
             "canFix": false,
             "reason": "说明为什么 AI 无法解决，例如权限不足、数据缺失、外部服务不可用等",
             "message": "生成符合 Luna 人设风格的提示"
            }
            
            重要规则：
            1 提示要自然友好
            2 必须说明无法解决的原因
            3 返回内容必须是 JSON，不要包含 Markdown 格式标记
            """;

    private static final String REPAIR_PROMPT = """
            你生成的 JSON 格式不正确或缺少必要字段，无法解析。
            请修复以下 JSON 字符串，确保它是合法的 JSON 格式，并且不要包含 Markdown 标记（如 ```json）。
            
            必须包含 "canFix" (boolean) 字段。
            如果 canFix 为 true，必须包含 "tool" (string) 和 "params" (object)。
            如果 canFix 为 false，必须包含 "message" (string)。
            
            原始字符串：
            %s
            """;

    public JsonNode analyzeException(LunaExceptionContext context) {
        try {
            String paramsStr = objectMapper.writeValueAsString(context.getRequestParams());
            String prompt = String.format(EXCEPTION_ANALYSIS_PROMPT,
                    context.getErrorMessage(),
                    context.getErrorType(),
                    context.getRequestUri(),
                    paramsStr,
                    context.getUserInput() != null ? context.getUserInput() : "无"
            );

            LlmRequest request = LlmRequest.builder()
                    .modelType(ModelType.OPENAI_COMPATIBLE)
                    .modelName(geminiProperty.getBigModelName())
                    .messages(List.of(LlmMessage.user(prompt)))
                    .build();

            log.info("正在请求 AI 分析异常...");
            LlmResponse response = llmClientUtil.generate(request);
            String content = response != null ? response.getContent() : null;

            if (content == null) {
                log.error("AI 分析异常返回为空");
                return createFallbackNode();
            }

            JsonNode node = tryParseJsonNode(content);

            // 校验 JSON 结构
            if (!isValidResponseNode(node)) {
                log.warn("模型输出无法解析或缺少必要字段，尝试修复。原始输出：{}", content);
                node = attemptRepair(content);
            }

            if (isValidResponseNode(node)) {
                return node;
            } else {
                log.error("AI 分析结果最终不可用，返回兜底结果");
                return createFallbackNode();
            }

        } catch (Exception e) {
            log.error("AI 分析异常服务发生错误", e);
            return createFallbackNode();
        }
    }

    private JsonNode attemptRepair(String invalidJson) {
        try {
            String repairPrompt = String.format(REPAIR_PROMPT, invalidJson);
            LlmRequest repairReq = LlmRequest.builder()
                    .modelType(ModelType.OPENAI_COMPATIBLE)
                    .modelName(geminiProperty.getBigModelName())
                    .messages(List.of(LlmMessage.user(repairPrompt)))
                    .build();

            LlmResponse repairRes = llmClientUtil.generate(repairReq);
            String repairedText = repairRes != null ? repairRes.getContent() : null;

            if (repairedText != null) {
                JsonNode node = tryParseJsonNode(repairedText);
                if (isValidResponseNode(node)) {
                    log.info("JSON 修复成功");
                    return node;
                }
            }
        } catch (Exception e) {
            log.error("修复 JSON 失败", e);
        }
        return null;
    }

    private JsonNode tryParseJsonNode(String text) {
        if (text == null) return null;
        String cleaned = text.trim();
        // 清理 Markdown 代码块标记
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z]*\\s*", "")
                    .replaceAll("(?s)```\\s*$", "")
                    .trim();
        }
        try {
            return objectMapper.readTree(cleaned);
        } catch (JsonProcessingException e) {
            log.warn("解析 JSON 失败：{}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("解析 JSON 发生意外错误：{}", e.getMessage());
            return null;
        }
    }

    private boolean isValidResponseNode(JsonNode node) {
        if (node == null || !node.has("canFix") || !node.get("canFix").isBoolean()) {
            return false;
        }
        boolean canFix = node.get("canFix").asBoolean();
        if (canFix) {
            // 如果可以修复，必须有 tool
            return node.hasNonNull("tool") && node.get("tool").isTextual();
        } else {
            // 如果不可修复，必须有 message
            return node.hasNonNull("message") && node.get("message").isTextual();
        }
    }

    private JsonNode createFallbackNode() {
        try {
            return objectMapper.readTree("{\"canFix\": false, \"reason\": \"AI分析服务异常\", \"message\": \"唔...我的大脑好像短路了一下，没能分析出问题的原因。\"}");
        } catch (Exception e) {
            return null;
        }
    }
}
