package org.yilena.luna.memory.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.memory.ThreeStageResponseService;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.List;
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
            TemplateBundle templates = resolveTemplateBundle(contextPackage);
            String taskDraft = callModel(
                    buildTaskDraftPrompt(userInput, toolContext, contextPackage, templates.taskTemplate(), templates.taskSpec()),
                    resolveTaskModelName(contextPackage)
            );
            String relationalDraft = callModel(
                    buildRelationalDraftPrompt(userInput, contextPackage, templates.relationalTemplate(), templates.relationalSpec()),
                    resolveSocialModelName(contextPackage)
            );
            String synthesis = callModel(
                    buildHybridSynthesisPrompt(taskDraft, relationalDraft, templates.hybridTemplate(), templates.hybridSpec()),
                    resolveSynthesisModelName()
            );
            return synthesis == null ? "" : synthesis.trim();
        } catch (Exception ignore) {
            return "";
        }
    }

    @Override
    public String generateFinalResponse(String userInput, String toolContext, StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return "";
        }
        try {
            TemplateBundle templates = resolveTemplateBundle(contextPackage);
            String taskDraft = callModel(
                    buildTaskDraftPrompt(userInput, toolContext, contextPackage, templates.taskTemplate(), templates.taskSpec()),
                    resolveTaskModelName(contextPackage)
            );
            String relationalDraft = callModel(
                    buildRelationalDraftPrompt(userInput, contextPackage, templates.relationalTemplate(), templates.relationalSpec()),
                    resolveSocialModelName(contextPackage)
            );
            String synthesis = callModel(
                    buildHybridSynthesisPrompt(taskDraft, relationalDraft, templates.hybridTemplate(), templates.hybridSpec()),
                    resolveSynthesisModelName()
            );
            return callModel(
                    buildFinalJsonPrompt(userInput, toolContext, synthesis, templates),
                    resolveSynthesisModelName()
            );
        } catch (Exception ignore) {
            return "";
        }
    }

    private TemplateBundle resolveTemplateBundle(StructuredContextPackage contextPackage) {
        Map<String, Object> synthesisPolicy = resolveSynthesisPolicy(contextPackage);
        String taskTemplate = getString(synthesisPolicy.get("task_template"), "execution_prompt");
        String relationalTemplate = getString(synthesisPolicy.get("relational_template"), "companion_prompt");
        String hybridTemplate = getString(synthesisPolicy.get("hybrid_template"), "task_with_empathy_prompt");
        Map<String, Object> taskSpec = asMap(synthesisPolicy.get("task_template_spec"));
        Map<String, Object> relationalSpec = asMap(synthesisPolicy.get("relational_template_spec"));
        Map<String, Object> hybridSpec = asMap(synthesisPolicy.get("hybrid_template_spec"));
        return new TemplateBundle(taskTemplate, relationalTemplate, hybridTemplate, taskSpec, relationalSpec, hybridSpec);
    }

    private Map<String, Object> resolveSynthesisPolicy(StructuredContextPackage contextPackage) {
        if (contextPackage.getPromptPolicy() == null) {
            return Map.of();
        }
        return asMap(contextPackage.getPromptPolicy().get("response_synthesis"));
    }

    private String buildTaskDraftPrompt(String userInput,
                                        String toolContext,
                                        StructuredContextPackage contextPackage,
                                        String template,
                                        Map<String, Object> templateSpec) throws Exception {
        String runtimeJson = objectMapper.writeValueAsString(contextPackage.getRuntime());
        String taskJson = objectMapper.writeValueAsString(contextPackage.getTaskContext());
        String specJson = objectMapper.writeValueAsString(templateSpec);
        return switch (template) {
            case "understanding_prompt" -> buildUnderstandingTaskPrompt(userInput, runtimeJson, taskJson, specJson);
            case "planning_prompt" -> buildPlanningTaskPrompt(userInput, toolContext, runtimeJson, taskJson, specJson);
            case "reflection_prompt" -> buildReflectionTaskPrompt(userInput, toolContext, runtimeJson, taskJson, specJson);
            case "reporting_prompt" -> buildReportingTaskPrompt(userInput, toolContext, runtimeJson, taskJson, specJson);
            case "execution_prompt" -> buildExecutionTaskPrompt(userInput, toolContext, runtimeJson, taskJson, specJson);
            default -> buildExecutionTaskPrompt(userInput, toolContext, runtimeJson, taskJson, specJson);
        };
    }

    private String buildRelationalDraftPrompt(String userInput,
                                              StructuredContextPackage contextPackage,
                                              String template,
                                              Map<String, Object> templateSpec) throws Exception {
        String relationJson = objectMapper.writeValueAsString(contextPackage.getRelationalContext());
        String socialDraftJson = objectMapper.writeValueAsString(
                contextPackage.getPromptPolicy() == null ? Map.of() : contextPackage.getPromptPolicy().getOrDefault("social_draft", Map.of())
        );
        String specJson = objectMapper.writeValueAsString(templateSpec);
        return switch (template) {
            case "emotional_support_prompt" -> buildEmotionalSupportPrompt(userInput, relationJson, socialDraftJson, specJson);
            case "repair_prompt" -> buildRepairPrompt(userInput, relationJson, socialDraftJson, specJson);
            case "celebration_prompt" -> buildCelebrationPrompt(userInput, relationJson, socialDraftJson, specJson);
            case "light_chat_prompt" -> buildLightChatPrompt(userInput, relationJson, socialDraftJson, specJson);
            case "companion_prompt" -> buildCompanionPrompt(userInput, relationJson, socialDraftJson, specJson);
            default -> buildCompanionPrompt(userInput, relationJson, socialDraftJson, specJson);
        };
    }

    private String buildHybridSynthesisPrompt(String taskDraft,
                                              String relationalDraft,
                                              String template,
                                              Map<String, Object> templateSpec) throws Exception {
        String specJson = objectMapper.writeValueAsString(templateSpec);
        return switch (template) {
            case "task_failure_with_support_prompt" -> buildTaskFailureWithSupportSynthesis(taskDraft, relationalDraft, specJson);
            case "clarify_with_warmth_prompt" -> buildClarifyWithWarmthSynthesis(taskDraft, relationalDraft, specJson);
            case "task_with_empathy_prompt" -> buildTaskWithEmpathySynthesis(taskDraft, relationalDraft, specJson);
            default -> buildTaskWithEmpathySynthesis(taskDraft, relationalDraft, specJson);
        };
    }

    private String buildUnderstandingTaskPrompt(String userInput, String runtimeJson, String taskJson, String specJson) {
        return """
                You are Task Brain: UNDERSTANDING mode.
                Produce a concise Chinese draft with sections:
                1) 目标理解
                2) 缺失信息
                3) 澄清问题
                4) 最小下一步
                Constraints:
                - Do not output execution details before requirements are clear.
                - Keep each section concrete and actionable.

                Template spec:
                %s

                User input:
                %s

                Runtime context:
                %s

                Task context:
                %s
                """.formatted(specJson, nonEmpty(userInput), runtimeJson, taskJson);
    }

    private String buildPlanningTaskPrompt(String userInput, String toolContext, String runtimeJson, String taskJson, String specJson) {
        return """
                You are Task Brain: PLANNING mode.
                Produce a concise Chinese plan draft with sections:
                1) 计划摘要
                2) 分阶段步骤
                3) 风险与缓解
                4) 需要确认
                Constraints:
                - Include dependency order and acceptance criteria.
                - Reuse tool outputs when available.

                Template spec:
                %s

                User input:
                %s

                Tool context:
                %s

                Runtime context:
                %s

                Task context:
                %s
                """.formatted(specJson, nonEmpty(userInput), nonEmpty(toolContext), runtimeJson, taskJson);
    }

    private String buildExecutionTaskPrompt(String userInput, String toolContext, String runtimeJson, String taskJson, String specJson) {
        return """
                You are Task Brain: EXECUTION mode.
                Produce a concise Chinese execution draft with sections:
                1) 当前结论
                2) 已执行动作
                3) 风险与阻塞
                4) 所需确认
                5) 下一步
                Constraints:
                - Keep factual and executable.
                - Never fabricate tool outcomes.

                Template spec:
                %s

                User input:
                %s

                Tool context:
                %s

                Runtime context:
                %s

                Task context:
                %s
                """.formatted(specJson, nonEmpty(userInput), nonEmpty(toolContext), runtimeJson, taskJson);
    }

    private String buildReflectionTaskPrompt(String userInput, String toolContext, String runtimeJson, String taskJson, String specJson) {
        return """
                You are Task Brain: REFLECTION mode.
                Produce a concise Chinese reflection draft with sections:
                1) 失败观察
                2) 根因判断
                3) 修复选项
                4) 推荐重规划路径
                Constraints:
                - Separate observed facts from assumptions.
                - End with one concrete recoverable action.

                Template spec:
                %s

                User input:
                %s

                Tool context:
                %s

                Runtime context:
                %s

                Task context:
                %s
                """.formatted(specJson, nonEmpty(userInput), nonEmpty(toolContext), runtimeJson, taskJson);
    }

    private String buildReportingTaskPrompt(String userInput, String toolContext, String runtimeJson, String taskJson, String specJson) {
        return """
                You are Task Brain: REPORTING mode.
                Produce a concise Chinese report draft with sections:
                1) 最终结果
                2) 关键证据
                3) 剩余风险
                4) 交付与后续
                Constraints:
                - Preserve key facts and unresolved points.
                - Avoid repeating internal reasoning text.

                Template spec:
                %s

                User input:
                %s

                Tool context:
                %s

                Runtime context:
                %s

                Task context:
                %s
                """.formatted(specJson, nonEmpty(userInput), nonEmpty(toolContext), runtimeJson, taskJson);
    }

    private String buildCompanionPrompt(String userInput, String relationJson, String socialDraftJson, String specJson) {
        return """
                You are Social Brain: COMPANION mode.
                Output concise Chinese relational draft with sections:
                1) 语气建议
                2) 共情先后
                3) 追问强度
                4) 收尾方式
                Constraints:
                - Keep natural, not performative.
                - Respect boundary hints.

                Template spec:
                %s

                User input:
                %s

                Relational context:
                %s

                Social draft:
                %s
                """.formatted(specJson, nonEmpty(userInput), relationJson, socialDraftJson);
    }

    private String buildLightChatPrompt(String userInput, String relationJson, String socialDraftJson, String specJson) {
        return """
                You are Social Brain: LIGHT_CHAT mode.
                Output concise Chinese relational draft with sections:
                1) 轻量语气
                2) 关系连续感
                3) 可选追问
                4) 结束节奏
                Constraints:
                - Do not over-analyze emotion.
                - Keep exchange fluid and friendly.

                Template spec:
                %s

                User input:
                %s

                Relational context:
                %s

                Social draft:
                %s
                """.formatted(specJson, nonEmpty(userInput), relationJson, socialDraftJson);
    }

    private String buildEmotionalSupportPrompt(String userInput, String relationJson, String socialDraftJson, String specJson) {
        return """
                You are Social Brain: EMOTIONAL_SUPPORT mode.
                Output concise Chinese relational draft with sections:
                1) 接住情绪
                2) 验证感受
                3) 最小负担下一步
                4) 温和确认
                Constraints:
                - Empathy first.
                - Keep question intensity low.
                - Avoid instructive or preachy tone.

                Template spec:
                %s

                User input:
                %s

                Relational context:
                %s

                Social draft:
                %s
                """.formatted(specJson, nonEmpty(userInput), relationJson, socialDraftJson);
    }

    private String buildRepairPrompt(String userInput, String relationJson, String socialDraftJson, String specJson) {
        return """
                You are Social Brain: REPAIR mode.
                Output concise Chinese relational draft with sections:
                1) 先承接不适
                2) 边界确认
                3) 修正后的回应策略
                4) 对齐确认
                Constraints:
                - No defensiveness.
                - Explicitly respect user's boundary and tone preference.

                Template spec:
                %s

                User input:
                %s

                Relational context:
                %s

                Social draft:
                %s
                """.formatted(specJson, nonEmpty(userInput), relationJson, socialDraftJson);
    }

    private String buildCelebrationPrompt(String userInput, String relationJson, String socialDraftJson, String specJson) {
        return """
                You are Social Brain: CELEBRATION mode.
                Output concise Chinese relational draft with sections:
                1) 正向肯定
                2) 成功归因
                3) 下一里程碑
                Constraints:
                - Keep positive but realistic.
                - Preserve momentum for next action.

                Template spec:
                %s

                User input:
                %s

                Relational context:
                %s

                Social draft:
                %s
                """.formatted(specJson, nonEmpty(userInput), relationJson, socialDraftJson);
    }

    private String buildTaskWithEmpathySynthesis(String taskDraft, String relationalDraft, String specJson) {
        return """
                You are Response Synthesizer.
                Hybrid template: task_with_empathy_prompt
                Merge two drafts into one concise Chinese guidance block.
                Constraints:
                - Preserve all task-critical information.
                - Tune tone using relational draft.
                - Keep output under 320 Chinese characters.

                Hybrid spec:
                %s

                Task draft:
                %s

                Relational draft:
                %s
                """.formatted(specJson, nonEmpty(taskDraft), nonEmpty(relationalDraft));
    }

    private String buildTaskFailureWithSupportSynthesis(String taskDraft, String relationalDraft, String specJson) {
        return """
                You are Response Synthesizer.
                Hybrid template: task_failure_with_support_prompt
                Merge drafts with order: empathy -> failure diagnosis -> recovery plan.
                Constraints:
                - Acknowledge user friction first.
                - Keep recovery plan concrete and minimal.
                - Keep output under 340 Chinese characters.

                Hybrid spec:
                %s

                Task draft:
                %s

                Relational draft:
                %s
                """.formatted(specJson, nonEmpty(taskDraft), nonEmpty(relationalDraft));
    }

    private String buildClarifyWithWarmthSynthesis(String taskDraft, String relationalDraft, String specJson) {
        return """
                You are Response Synthesizer.
                Hybrid template: clarify_with_warmth_prompt
                Merge drafts for clarification-first response.
                Constraints:
                - Light empathy, then ask precise clarification.
                - Avoid heavy emotional language.
                - Keep output under 280 Chinese characters.

                Hybrid spec:
                %s

                Task draft:
                %s

                Relational draft:
                %s
                """.formatted(specJson, nonEmpty(taskDraft), nonEmpty(relationalDraft));
    }

    private String buildFinalJsonPrompt(String userInput, String toolContext, String synthesis, TemplateBundle templates) throws Exception {
        String templateMeta = objectMapper.writeValueAsString(Map.of(
                "task_template", templates.taskTemplate(),
                "relational_template", templates.relationalTemplate(),
                "hybrid_template", templates.hybridTemplate()
        ));
        return """
                You are Final Response Composer.
                Return JSON only with keys:
                - emotion: short tone label
                - reply: final Chinese reply for user
                Constraints:
                - Keep task information complete and truthful.
                - Follow the synthesis guidance first.
                - No markdown code block and no extra keys.

                Template meta:
                %s

                User input:
                %s

                Tool context:
                %s

                Synthesis guidance:
                %s
                """.formatted(templateMeta, nonEmpty(userInput), nonEmpty(toolContext), nonEmpty(synthesis));
    }

    private String callModel(String prompt, String modelName) {
        try {
            LlmRequest request = LlmRequest.builder()
                    .modelType(ModelType.OPENAI_COMPATIBLE)
                    .modelName(modelName)
                    .messages(List.of(LlmMessage.user(prompt)))
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

    private String resolveTaskModelName(StructuredContextPackage contextPackage) {
        TaskRuntimeState taskState = contextPackage.getTaskState();
        if ((taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING || taskState == TaskRuntimeState.EXECUTING)
                && geminiProperty.getCode() != null && geminiProperty.getCode().getModelName() != null) {
            return geminiProperty.getCode().getModelName();
        }
        if (geminiProperty.getBig() != null && geminiProperty.getBig().getModelName() != null) {
            return geminiProperty.getBig().getModelName();
        }
        return geminiProperty.getFlash().getModelName();
    }

    private String resolveSocialModelName(StructuredContextPackage contextPackage) {
        RelationalRuntimeState relationalState = contextPackage.getRelationalState();
        if ((relationalState == RelationalRuntimeState.EMOTIONAL_SUPPORT
                || relationalState == RelationalRuntimeState.FRAGILE_MOMENT
                || relationalState == RelationalRuntimeState.REPAIRING)
                && geminiProperty.getChat() != null && geminiProperty.getChat().getModelName() != null) {
            return geminiProperty.getChat().getModelName();
        }
        if (geminiProperty.getFlash() != null && geminiProperty.getFlash().getModelName() != null) {
            return geminiProperty.getFlash().getModelName();
        }
        return geminiProperty.getBig().getModelName();
    }

    private String resolveSynthesisModelName() {
        if (geminiProperty.getFlash() != null && geminiProperty.getFlash().getModelName() != null) {
            return geminiProperty.getFlash().getModelName();
        }
        return geminiProperty.getBig().getModelName();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String getString(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = Objects.toString(value, fallback);
        return text == null || text.isBlank() ? fallback : text;
    }

    private record TemplateBundle(String taskTemplate,
                                  String relationalTemplate,
                                  String hybridTemplate,
                                  Map<String, Object> taskSpec,
                                  Map<String, Object> relationalSpec,
                                  Map<String, Object> hybridSpec) {
    }
}
