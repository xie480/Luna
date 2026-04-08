package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.yilena.luna.adapter.LlmAdapter;
import org.yilena.luna.exception.LunaExceptionContext;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.service.ExceptionAgentService;

/**
 * 異常分析 Agent 實現
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExceptionAgentServiceImpl implements ExceptionAgentService {

    private final LlmAdapter llmAdapter;
    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private PromptRegistryService promptRegistryService;

    @Override
    public JsonNode analyzeException(LunaExceptionContext context) {
        try {
            // 1. 構建分析 Prompt
            String prompt = String.format(resolvePromptValue("task.exception.analysis_v1", PromptTemplates.EXCEPTION_ANALYSIS_PROMPT),
                    context.getErrorMessage(),
                    context.getErrorType(),
                    context.getRequestUri(),
                    context.getRequestParams(),
                    context.getUserInput()
            );

            // 2. 調用 LLM
            String response = llmAdapter.generate(prompt);
            
            // 3. 解析結果
            JsonNode result = parseJson(response);
            
            // 4. 如果解析失敗，嘗試修復
            if (result == null) {
                log.warn("異常分析結果 JSON 解析失敗，嘗試修復...");
                String repairPrompt = String.format(resolvePromptValue("repair.exception.json_v1", PromptTemplates.EXCEPTION_JSON_REPAIR_PROMPT), response);
                String repairedResponse = llmAdapter.generate(repairPrompt);
                result = parseJson(repairedResponse);
            }

            return result;

        } catch (Exception e) {
            log.error("異常分析 Agent 執行失敗", e);
            return null;
        }
    }

    private JsonNode parseJson(String text) {
        if (text == null) return null;
        try {
            String clean = text.trim().replace("```json", "").replace("```", "");
            return objectMapper.readTree(clean);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolvePromptValue(String key, String fallback) {
        if (promptRegistryService == null) {
            return fallback;
        }
        return promptRegistryService.resolvePromptValue(key, fallback);
    }
}
