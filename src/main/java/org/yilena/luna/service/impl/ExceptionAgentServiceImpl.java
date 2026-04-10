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
 * 异常分析代理服务实现，负责将异常上下文交给模型分析，生成可用于自动修复或用户提示的结构化结论。
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
            /**
             * 先根据异常上下文拼装分析 Prompt，让模型能看到错误类型、请求参数和用户输入等关键信息。
             */
            String prompt = String.format(resolvePromptValue("task.exception.analysis_v1", PromptTemplates.EXCEPTION_ANALYSIS_PROMPT),
                    context.getErrorMessage(),
                    context.getErrorType(),
                    context.getRequestUri(),
                    context.getRequestParams(),
                    context.getUserInput()
            );

            /**
             * 调用大模型生成异常分析结果，作为后续自动修复判断的输入。
             */
            String response = llmAdapter.generate(prompt);

            /**
             * 优先按标准 JSON 解析模型返回，便于下游直接读取 canFix、tool、params 等字段。
             */
            JsonNode result = parseJson(response);

            /**
             * 如果模型输出格式不稳定，则再走一次 JSON 修复 Prompt，尽量保住可机读结构。
             */
            if (result == null) {
                log.warn("异常分析结果 JSON 解析失败，尝试修复输出格式");
                String repairPrompt = String.format(
                        resolvePromptValue("repair.exception.json_v1", PromptTemplates.EXCEPTION_JSON_REPAIR_PROMPT),
                        response
                );
                String repairedResponse = llmAdapter.generate(repairPrompt);
                result = parseJson(repairedResponse);
            }
            return result;
        } catch (Exception e) {
            log.error("异常分析代理执行失败", e);
            return null;
        }
    }

    /**
     * 将模型输出安全解析为 JSON，兼容 Markdown 代码块包装。
     */
    private JsonNode parseJson(String text) {
        if (text == null) {
            return null;
        }
        try {
            String clean = text.trim().replace("```json", "").replace("```", "");
            return objectMapper.readTree(clean);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 优先从治理中心读取 Prompt，未配置时退回默认模板。
     */
    private String resolvePromptValue(String key, String fallback) {
        if (promptRegistryService == null) {
            return fallback;
        }
        return promptRegistryService.resolvePromptValue(key, fallback);
    }
}
