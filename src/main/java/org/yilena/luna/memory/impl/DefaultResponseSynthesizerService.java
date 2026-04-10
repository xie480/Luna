package org.yilena.luna.memory.impl;

import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.ResponseSynthesizerService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
/**
 * 响应合成策略服务默认实现，负责根据任务态与关系态选择任务模板、关系模板和混合模板，
 * 为最终回复生成提供结构化策略配置。
 */
public class DefaultResponseSynthesizerService implements ResponseSynthesizerService {

    @Override
    /**
     * 构建当前轮次的响应合成策略，明确三阶段草稿和最终融合方式。
     */
    public Map<String, Object> buildSynthesisPolicy(TaskRuntimeState taskState,
                                                    RelationalRuntimeState relationalState,
                                                    Map<String, Object> taskContext,
                                                    Map<String, Object> relationalContext,
                                                    Map<String, Object> socialDraft) {
        /**
         * 先分别确定任务侧、关系侧和混合阶段的模板规格，
         * 再汇总为统一的回复合成策略。
         */
        TaskRuntimeState safeTaskState = taskState == null ? TaskRuntimeState.UNDERSTANDING : taskState;
        RelationalRuntimeState safeRelationalState = relationalState == null ? RelationalRuntimeState.LIGHT_CHAT : relationalState;
        Map<String, Object> taskTemplateSpec = buildTaskTemplateSpec(safeTaskState, taskContext);
        Map<String, Object> relationalTemplateSpec = buildRelationalTemplateSpec(safeRelationalState, relationalContext, socialDraft);
        Map<String, Object> hybridTemplateSpec = buildHybridTemplateSpec(safeTaskState, safeRelationalState);

        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("stage_1", "task_draft");
        policy.put("stage_2", "relational_draft");
        policy.put("stage_3", "synthesis");
        policy.put("task_template", taskTemplateSpec.get("template"));
        policy.put("relational_template", relationalTemplateSpec.get("template"));
        policy.put("hybrid_template", hybridTemplateSpec.get("template"));
        policy.put("task_template_spec", taskTemplateSpec);
        policy.put("relational_template_spec", relationalTemplateSpec);
        policy.put("hybrid_template_spec", hybridTemplateSpec);
        policy.put("merge_strategy", "task_content_first_then_social_tuning");
        policy.put("non_lossy_requirement", true);
        policy.put("template_implementation_mode", "independent_template_specs");
        policy.put("social_draft", socialDraft == null ? Map.of() : socialDraft);
        return policy;
    }

    private Map<String, Object> buildTaskTemplateSpec(TaskRuntimeState state, Map<String, Object> taskContext) {
        /**
         * 根据任务执行阶段选择不同的任务回复模板，
         * 保证回复内容与当前业务推进目标一致。
         */
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("context_anchor", taskContext == null ? Map.of() : taskContext);
        switch (state) {
            case UNDERSTANDING, CONTEXT_BUILDING -> {
                spec.put("template", "understanding_prompt");
                spec.put("objective", "clarify goal, constraints, and missing information");
                spec.put("sections", List.of("goal_hypothesis", "missing_info", "clarification_questions", "minimal_next_step"));
                spec.put("hard_constraints", List.of("avoid premature execution details", "surface ambiguity explicitly"));
            }
            case PLANNING, REPLANNING, WAITING_PLAN_CONFIRMATION -> {
                spec.put("template", "planning_prompt");
                spec.put("objective", "provide an executable plan with phase/node granularity");
                spec.put("sections", List.of("plan_summary", "phases_and_nodes", "risk_and_mitigation", "confirmation_request"));
                spec.put("hard_constraints", List.of("include dependencies", "include acceptance criteria"));
            }
            case EXECUTING, WAITING_TOOL, WAITING_USER, WAITING_APPROVAL -> {
                spec.put("template", "execution_prompt");
                spec.put("objective", "drive current node execution and status updates");
                spec.put("sections", List.of("current_progress", "actions_taken", "blocking_factors", "next_operation"));
                spec.put("hard_constraints", List.of("report tool outcomes faithfully", "no fabricated runtime status"));
            }
            case REFLECTING, FAILED -> {
                spec.put("template", "reflection_prompt");
                spec.put("objective", "diagnose failures and propose recovery path");
                spec.put("sections", List.of("failure_observation", "root_cause", "repair_options", "recommended_replan"));
                spec.put("hard_constraints", List.of("explicitly separate facts and assumptions", "provide one concrete recovery step"));
            }
            case REPORTING, COMPLETED -> {
                spec.put("template", "reporting_prompt");
                spec.put("objective", "deliver concise and complete final summary");
                spec.put("sections", List.of("final_outcome", "key_deliverables", "risks_left", "handoff_next_step"));
                spec.put("hard_constraints", List.of("preserve key evidence", "do not hide unresolved items"));
            }
            default -> {
                spec.put("template", "understanding_prompt");
                spec.put("objective", "clarify user intent and expected deliverable");
                spec.put("sections", List.of("goal_hypothesis", "missing_info", "next_step"));
                spec.put("hard_constraints", List.of("no over-commitment"));
            }
        }
        return spec;
    }

    private Map<String, Object> buildRelationalTemplateSpec(RelationalRuntimeState state,
                                                            Map<String, Object> relationalContext,
                                                            Map<String, Object> socialDraft) {
        /**
         * 根据关系状态选择对应的社交表达模板，
         * 控制回复的情绪强度、边界感和互动目标。
         */
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("context_anchor", relationalContext == null ? Map.of() : relationalContext);
        spec.put("social_draft", socialDraft == null ? Map.of() : socialDraft);
        switch (state) {
            case EMOTIONAL_SUPPORT, FRAGILE_MOMENT -> {
                spec.put("template", "emotional_support_prompt");
                spec.put("objective", "stabilize emotion first, then gently restore task momentum");
                spec.put("sections", List.of("empathy_opening", "validation", "smallest_next_step", "gentle_check_in"));
                spec.put("hard_constraints", List.of("avoid preachy tone", "question intensity must stay low"));
            }
            case REPAIRING -> {
                spec.put("template", "repair_prompt");
                spec.put("objective", "repair alignment after discomfort or misunderstanding");
                spec.put("sections", List.of("acknowledgement", "responsibility_boundary", "realigned_response", "alignment_confirmation"));
                spec.put("hard_constraints", List.of("no defensiveness", "explicitly acknowledge user boundary"));
            }
            case CELEBRATING -> {
                spec.put("template", "celebration_prompt");
                spec.put("objective", "amplify positive momentum without losing realism");
                spec.put("sections", List.of("recognition", "what_worked", "next_milestone"));
                spec.put("hard_constraints", List.of("avoid exaggerated claims", "keep actionable direction"));
            }
            case LIGHT_CHAT, COMPANION_MODE, DEEP_TALK -> {
                spec.put("template", "light_chat_prompt");
                spec.put("objective", "maintain natural continuity and friendly companionship");
                spec.put("sections", List.of("tone_alignment", "conversational_warmth", "optional_follow_up"));
                spec.put("hard_constraints", List.of("respect established boundary preferences"));
            }
            default -> {
                spec.put("template", "companion_prompt");
                spec.put("objective", "build trust with neutral warmth");
                spec.put("sections", List.of("stable_tone", "small_connection_signal", "task_ready_transition"));
                spec.put("hard_constraints", List.of("avoid over-familiarity"));
            }
        }
        return spec;
    }

    private Map<String, Object> buildHybridTemplateSpec(TaskRuntimeState taskState, RelationalRuntimeState relationalState) {
        /**
         * 在任务内容与关系表达之间确定最终融合模板，
         * 确保任务事实不丢失，同时保留必要的社交调谐。
         */
        Map<String, Object> spec = new LinkedHashMap<>();
        String template = pickHybridTemplate(taskState, relationalState);
        spec.put("template", template);
        if ("task_failure_with_support_prompt".equals(template)) {
            spec.put("objective", "acknowledge friction and provide recovery path with emotional buffering");
            spec.put("blend_order", List.of("empathy", "failure_diagnosis", "recovery_plan"));
        } else if ("clarify_with_warmth_prompt".equals(template)) {
            spec.put("objective", "clarify missing requirements while preserving conversational warmth");
            spec.put("blend_order", List.of("light_empathy", "clarification", "confirm_next_step"));
        } else {
            spec.put("objective", "deliver task-complete answer with natural social calibration");
            spec.put("blend_order", List.of("task_core", "tone_adjustment", "closing"));
        }
        spec.put("hard_constraints", List.of("task facts are lossless", "tone follows relational boundaries"));
        return spec;
    }

    private String pickHybridTemplate(TaskRuntimeState taskState, RelationalRuntimeState relationalState) {
        if (relationalState == RelationalRuntimeState.EMOTIONAL_SUPPORT || relationalState == RelationalRuntimeState.FRAGILE_MOMENT) {
            if (taskState == TaskRuntimeState.FAILED || taskState == TaskRuntimeState.REFLECTING) {
                return "task_failure_with_support_prompt";
            }
            return "task_with_empathy_prompt";
        }
        if (taskState == TaskRuntimeState.UNDERSTANDING || taskState == TaskRuntimeState.CONTEXT_BUILDING) {
            return "clarify_with_warmth_prompt";
        }
        return "task_with_empathy_prompt";
    }
}
