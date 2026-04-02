package org.yilena.luna.context.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.InputReconstructionAgent;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class DefaultInputReconstructionAgent implements InputReconstructionAgent {

    @Override
    public InputReconstructionResult reconstruct(String sessionId,
                                                 String userInput,
                                                 StructuredContextPackage contextPackage,
                                                 TaskRuntimeState taskState,
                                                 RelationalRuntimeState relationalState) {
        String input = userInput == null ? "" : userInput.trim();
        String lower = input.toLowerCase(Locale.ROOT);
        Map<String, String> entities = extractEntities(input, contextPackage);
        String explicitGoal = resolveExplicitGoal(input, contextPackage);
        String timeScope = inferTimeScope(lower);
        List<String> missingSlots = inferMissingSlots(lower);
        List<String> constraints = inferConstraints(lower, contextPackage);
        String normalizedIntent = buildNormalizedIntent(input, explicitGoal, entities, constraints, timeScope, taskState, relationalState);
        String ragQuery = buildRagQuery(normalizedIntent, entities, constraints, timeScope, taskState);
        String mcpQuery = buildMcpQuery(normalizedIntent, explicitGoal, entities, constraints, taskState);
        String blueprintHint = buildBlueprintHint(explicitGoal, taskState, constraints, timeScope);
        double confidence = scoreConfidence(input, missingSlots, entities, explicitGoal, taskState);
        return InputReconstructionResult.builder()
                .normalizedUserIntent(normalizedIntent)
                .explicitTaskGoal(explicitGoal)
                .clarifiedEntities(entities)
                .missingSlots(missingSlots)
                .timeScope(timeScope)
                .businessConstraints(constraints)
                .reformulatedQueryForRag(ragQuery)
                .reformulatedQueryForMcp(mcpQuery)
                .blueprintHint(blueprintHint)
                .intentConfidence(confidence)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extractEntities(String userInput, StructuredContextPackage contextPackage) {
        Map<String, String> out = new LinkedHashMap<>();
        if (contextPackage != null && contextPackage.getTaskContext() != null) {
            Object working = contextPackage.getTaskContext().get("working_memory");
            if (working instanceof Map<?, ?> map) {
                String goal = asText(((Map<String, Object>) map).get("goal_refined"));
                if (!goal.isBlank()) {
                    out.put("working_goal", goal);
                }
                String phase = asText(((Map<String, Object>) map).get("active_phase_id"));
                if (!phase.isBlank()) {
                    out.put("active_phase_id", phase);
                }
            }
        }
        if (userInput != null && !userInput.isBlank()) {
            if (containsAny(userInput.toLowerCase(Locale.ROOT), "q1", "q2", "q3", "q4")) {
                out.put("quarter", firstQuarter(userInput));
            }
            if (containsAny(userInput.toLowerCase(Locale.ROOT), "today", "明天", "tomorrow", "本周", "this week")) {
                out.put("time_signal", "present");
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private String resolveExplicitGoal(String input, StructuredContextPackage contextPackage) {
        if (contextPackage != null && contextPackage.getTaskContext() != null) {
            Object working = contextPackage.getTaskContext().get("working_memory");
            if (working instanceof Map<?, ?> map) {
                String refined = asText(((Map<String, Object>) map).get("goal_refined"));
                if (!refined.isBlank()) {
                    return refined;
                }
            }
        }
        return input == null ? "" : input;
    }

    private String inferTimeScope(String lower) {
        if (containsAny(lower, "today", "今天", "今晚")) {
            return "today";
        }
        if (containsAny(lower, "tomorrow", "明天")) {
            return "tomorrow";
        }
        if (containsAny(lower, "this week", "本周", "这周")) {
            return "this_week";
        }
        if (containsAny(lower, "this month", "本月", "这个月")) {
            return "this_month";
        }
        return "unspecified";
    }

    private List<String> inferMissingSlots(String lower) {
        List<String> missing = new ArrayList<>();
        if (containsAny(lower, "这个", "那个", "it", "this", "that", "再来一次", "继续")) {
            missing.add("target_reference");
        }
        if (containsAny(lower, "帮我", "please", "分析", "处理") && !containsAny(lower, "怎么", "what", "如何", "目标")) {
            missing.add("success_criteria");
        }
        return missing;
    }

    @SuppressWarnings("unchecked")
    private List<String> inferConstraints(String lower, StructuredContextPackage contextPackage) {
        List<String> out = new ArrayList<>();
        if (containsAny(lower, "不要", "别", "must", "必须", "only", "仅")) {
            out.add("hard_constraint_from_user_input");
        }
        if (contextPackage != null && contextPackage.getTaskContext() != null) {
            Object working = contextPackage.getTaskContext().get("working_memory");
            if (working instanceof Map<?, ?> map) {
                String constraintsJson = asText(((Map<String, Object>) map).get("constraints_json"));
                if (!constraintsJson.isBlank()) {
                    out.add("working_memory_constraints");
                }
            }
        }
        return out;
    }

    private String buildNormalizedIntent(String input,
                                         String explicitGoal,
                                         Map<String, String> entities,
                                         List<String> constraints,
                                         String timeScope,
                                         TaskRuntimeState taskState,
                                         RelationalRuntimeState relationalState) {
        return "goal=" + explicitGoal
                + "; taskState=" + safeName(taskState)
                + "; relationState=" + safeName(relationalState)
                + "; timeScope=" + timeScope
                + "; entities=" + entities
                + "; constraints=" + constraints
                + "; original=" + (input == null ? "" : input);
    }

    private String buildRagQuery(String normalizedIntent,
                                 Map<String, String> entities,
                                 List<String> constraints,
                                 String timeScope,
                                 TaskRuntimeState taskState) {
        return normalizedIntent
                + " | retrieval_target=knowledge,memory,preference"
                + " | stage=" + safeName(taskState)
                + " | entities=" + entities
                + " | constraints=" + constraints
                + " | timeScope=" + timeScope;
    }

    private String buildMcpQuery(String normalizedIntent,
                                 String explicitGoal,
                                 Map<String, String> entities,
                                 List<String> constraints,
                                 TaskRuntimeState taskState) {
        return "task_goal=" + explicitGoal
                + " | stage=" + safeName(taskState)
                + " | entities=" + entities
                + " | constraints=" + constraints
                + " | intent=" + normalizedIntent;
    }

    private String buildBlueprintHint(String explicitGoal,
                                      TaskRuntimeState taskState,
                                      List<String> constraints,
                                      String timeScope) {
        return "stage=" + safeName(taskState)
                + ", goal=" + explicitGoal
                + ", timeScope=" + timeScope
                + ", constraints=" + constraints;
    }

    private double scoreConfidence(String input,
                                   List<String> missingSlots,
                                   Map<String, String> entities,
                                   String explicitGoal,
                                   TaskRuntimeState taskState) {
        double score = 0.45;
        if (input != null && !input.isBlank()) {
            score += 0.15;
        }
        if (explicitGoal != null && !explicitGoal.isBlank()) {
            score += 0.15;
        }
        score += Math.min(0.10, entities.size() * 0.03);
        score += taskState == TaskRuntimeState.EXECUTING || taskState == TaskRuntimeState.PLANNING ? 0.08 : 0.04;
        score -= Math.min(0.20, missingSlots.size() * 0.07);
        if (score < 0.20) {
            return 0.20;
        }
        return Math.min(score, 0.98);
    }

    private boolean containsAny(String text, String... words) {
        if (text == null || words == null) {
            return false;
        }
        for (String word : words) {
            if (word != null && !word.isBlank() && text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String firstQuarter(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.contains("q1")) {
            return "Q1";
        }
        if (lower.contains("q2")) {
            return "Q2";
        }
        if (lower.contains("q3")) {
            return "Q3";
        }
        if (lower.contains("q4")) {
            return "Q4";
        }
        return "";
    }

    private String safeName(Enum<?> value) {
        return value == null ? "UNKNOWN" : value.name();
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}

