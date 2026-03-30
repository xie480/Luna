package org.yilena.luna.memory.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.memory.ThreeStageResponseService;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DefaultThreeStageResponseService implements ThreeStageResponseService {

    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final ObjectMapper objectMapper;

    @Override
    public String generateSynthesisBrief(String userInput, String toolContext, StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return "";
        }
        try {
            String taskTemplate = findTemplate(contextPackage, "task_template", "execution_prompt");
            String relationTemplate = findTemplate(contextPackage, "relational_template", "companion_prompt");
            String hybridTemplate = findTemplate(contextPackage, "hybrid_template", "task_with_empathy_prompt");

            String taskDraft = callModel(buildTaskDraftPrompt(userInput, toolContext, contextPackage, taskTemplate));
            String relationalDraft = callModel(buildRelationalDraftPrompt(userInput, contextPackage, relationTemplate));
            String synthesis = callModel(buildSynthesisPrompt(taskDraft, relationalDraft, hybridTemplate));
            return synthesis == null ? "" : synthesis.trim();
        } catch (Exception ignore) {
            return "";
        }
    }

    private String findTemplate(StructuredContextPackage contextPackage, String key, String fallback) {
        if (contextPackage.getPromptPolicy() == null) {
            return fallback;
        }
        Object synthesis = contextPackage.getPromptPolicy().get("response_synthesis");
        if (!(synthesis instanceof Map<?, ?> map)) {
            return fallback;
        }
        Object raw = map.get(key);
        return raw == null ? fallback : Objects.toString(raw, fallback);
    }

    private String buildTaskDraftPrompt(String userInput,
                                        String toolContext,
                                        StructuredContextPackage contextPackage,
                                        String templateName) throws Exception {
        String taskContextJson = objectMapper.writeValueAsString(contextPackage.getTaskContext());
        String runtimeJson = objectMapper.writeValueAsString(contextPackage.getRuntime());
        String tool = toolContext == null ? "" : toolContext;
        return """
                You are the Task Brain.
                Template: %s
                Goal: produce a concise Task Draft in Chinese with these sections:
                1) 结论
                2) 步骤
                3) 风险
                4) 所需确认
                5) 下一步
                Keep factual and executable.

                User input:
                %s

                Runtime:
                %s

                Task context:
                %s

                Tool context:
                %s
                """.formatted(templateName, userInput, runtimeJson, taskContextJson, tool);
    }

    private String buildRelationalDraftPrompt(String userInput,
                                              StructuredContextPackage contextPackage,
                                              String templateName) throws Exception {
        String relationJson = objectMapper.writeValueAsString(contextPackage.getRelationalContext());
        Object socialDraft = contextPackage.getPromptPolicy() == null
                ? null
                : contextPackage.getPromptPolicy().get("response_synthesis");
        String synthesisJson = objectMapper.writeValueAsString(socialDraft);
        return """
                You are the Social Brain.
                Template: %s
                Output a concise Relational Draft in Chinese:
                1) 语气建议
                2) 是否先共情
                3) 追问强度
                4) 称呼与收尾策略
                Keep grounded on user state and boundaries.

                User input:
                %s

                Relational context:
                %s

                Social policy:
                %s
                """.formatted(templateName, userInput, relationJson, synthesisJson);
    }

    private String buildSynthesisPrompt(String taskDraft,
                                        String relationalDraft,
                                        String templateName) {
        return """
                You are the Response Synthesizer.
                Template: %s
                Merge Task Draft + Relational Draft into one Chinese guidance block for the final responder.
                Constraints:
                - Preserve all task-critical info.
                - Style must be warm but not performative.
                - Keep output under 300 Chinese characters.

                Task Draft:
                %s

                Relational Draft:
                %s
                """.formatted(templateName, nonEmpty(taskDraft), nonEmpty(relationalDraft));
    }

    private String callModel(String prompt) {
        try {
            LlmRequest request = LlmRequest.builder()
                    .modelType(ModelType.OPENAI_COMPATIBLE)
                    .modelName(geminiProperty.getFlash().getModelName())
                    .messages(java.util.List.of(LlmMessage.user(prompt)))
                    .enablePromptInjectionCheck(true)
                    .build();
            LlmResponse response = llmClientUtil.generate(request);
            return response == null ? "" : response.getContent();
        } catch (Exception ignore) {
            return "";
        }
    }

    private String nonEmpty(String text) {
        return text == null || text.isBlank() ? "(empty)" : text.trim();
    }
}
