package org.yilena.luna.memory.impl;

import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.ResponseSynthesizerService;

import java.util.HashMap;
import java.util.Map;

@Service
public class DefaultResponseSynthesizerService implements ResponseSynthesizerService {

    @Override
    public Map<String, Object> buildSynthesisPolicy(TaskRuntimeState taskState,
                                                    RelationalRuntimeState relationalState,
                                                    Map<String, Object> taskContext,
                                                    Map<String, Object> relationalContext,
                                                    Map<String, Object> socialDraft) {
        Map<String, Object> policy = new HashMap<>();
        policy.put("stage_1", "task_draft");
        policy.put("stage_2", "relational_draft");
        policy.put("stage_3", "synthesis");
        policy.put("task_template", pickTaskTemplate(taskState));
        policy.put("relational_template", pickRelationalTemplate(relationalState));
        policy.put("hybrid_template", pickHybridTemplate(taskState, relationalState));
        policy.put("merge_strategy", "task_content_first_then_social_tuning");
        policy.put("non_lossy_requirement", true);
        policy.put("social_draft", socialDraft == null ? Map.of() : socialDraft);
        return policy;
    }

    private String pickTaskTemplate(TaskRuntimeState state) {
        return switch (state) {
            case UNDERSTANDING -> "understanding_prompt";
            case PLANNING, REPLANNING -> "planning_prompt";
            case EXECUTING, WAITING_TOOL, WAITING_USER, WAITING_APPROVAL -> "execution_prompt";
            case REFLECTING, FAILED -> "reflection_prompt";
            case REPORTING, COMPLETED -> "reporting_prompt";
            default -> "understanding_prompt";
        };
    }

    private String pickRelationalTemplate(RelationalRuntimeState state) {
        return switch (state) {
            case EMOTIONAL_SUPPORT, FRAGILE_MOMENT -> "emotional_support_prompt";
            case REPAIRING -> "repair_prompt";
            case CELEBRATING -> "celebration_prompt";
            case LIGHT_CHAT, COMPANION_MODE -> "light_chat_prompt";
            default -> "companion_prompt";
        };
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
