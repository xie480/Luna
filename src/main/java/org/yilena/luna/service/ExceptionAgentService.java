package org.yilena.luna.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.exception.LunaExceptionContext;
import org.yilena.luna.llm.LlmResponse;

/**
 * 异常分析 Agent 服务
 * 负责构建 Prompt 并调用 LLM 进行决策
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExceptionAgentService {

    private final ChatLanguageModel chatLanguageModel;
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

            log.info("正在请求 AI 分析异常...");
            LlmResponse response = llmClientUtil.generate(request);
            
            // 清理可能的 Markdown 标记
            response = response.replace("```json", "").replace("```", "").trim();
            
            return objectMapper.readTree(response);
        } catch (Exception e) {
            log.error("AI 分析异常失败", e);
            // 返回默认无法修复的结构
            try {
                return objectMapper.readTree("{\"canFix\": false, \"reason\": \"AI分析服务异常\", \"message\": \"唔...我的大脑好像短路了一下，没能分析出问题的原因。\"}");
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
