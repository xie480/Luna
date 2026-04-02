package org.yilena.luna.context.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.InputReconstructionAgent;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DefaultInputReconstructionAgent implements InputReconstructionAgent {

    private static final Pattern DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern QUARTER_PATTERN = Pattern.compile("\\bq([1-4])\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public InputReconstructionResult reconstruct(String sessionId,
                                                 String userInput,
                                                 StructuredContextPackage contextPackage,
                                                 TaskRuntimeState taskState,
                                                 RelationalRuntimeState relationalState) {
        String input = normalize(userInput);
        ContextSignals signals = collectSignals(contextPackage);
        Map<String, String> entities = extractEntities(input, signals, contextPackage);
        String explicitGoal = resolveExplicitGoal(input, signals, contextPackage);
        String timeScope = inferTimeScope(input, signals);
        List<String> constraints = inferConstraints(input, signals, contextPackage);
        List<String> missingSlots = inferMissingSlots(input, explicitGoal, entities, constraints, taskState);
        String normalizedIntent = buildNormalizedIntent(input, explicitGoal, entities, constraints, missingSlots, timeScope, taskState, relationalState, signals);
        String ragQuery = buildRagQuery(explicitGoal, entities, constraints, timeScope, taskState, signals);
        String mcpQuery = buildMcpQuery(explicitGoal, entities, constraints, taskState, signals);
        String blueprintHint = buildBlueprintHint(explicitGoal, taskState, constraints, timeScope, signals);
        double confidence = scoreConfidence(input, missingSlots, entities, explicitGoal, taskState, signals);
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
    private Map<String, String> extractEntities(String userInput, ContextSignals signals, StructuredContextPackage contextPackage) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!signals.goalFromState().isBlank()) {
            out.put("working_goal", signals.goalFromState());
        }
        if (!signals.currentNode().isBlank()) {
            out.put("active_node", signals.currentNode());
        }
        if (!signals.lastToolName().isBlank()) {
            out.put("latest_tool", signals.lastToolName());
        }
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
        if (!userInput.isBlank()) {
            Matcher quarter = QUARTER_PATTERN.matcher(userInput);
            if (quarter.find()) {
                out.put("quarter", "Q" + quarter.group(1));
            }
            List<String> dates = extractMatches(DATE_PATTERN, userInput, 3);
            if (!dates.isEmpty()) {
                out.put("dates", String.join(",", dates));
            }
            List<String> numbers = extractMatches(NUMBER_PATTERN, userInput, 5);
            if (!numbers.isEmpty()) {
                out.put("numbers", String.join(",", numbers));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private String resolveExplicitGoal(String input, ContextSignals signals, StructuredContextPackage contextPackage) {
        if (!signals.goalFromState().isBlank()) {
            return signals.goalFromState();
        }
        if (contextPackage != null && contextPackage.getTaskContext() != null) {
            Object working = contextPackage.getTaskContext().get("working_memory");
            if (working instanceof Map<?, ?> map) {
                String refined = asText(((Map<String, Object>) map).get("goal_refined"));
                if (!refined.isBlank()) {
                    return refined;
                }
            }
        }
        if (!signals.lastSummary().isBlank()) {
            return signals.lastSummary();
        }
        return input;
    }

    private String inferTimeScope(String input, ContextSignals signals) {
        String lower = normalize(input).toLowerCase(Locale.ROOT);
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
        if (!signals.latestTimeScope().isBlank()) {
            return signals.latestTimeScope();
        }
        return "unspecified";
    }

    private List<String> inferMissingSlots(String input,
                                           String explicitGoal,
                                           Map<String, String> entities,
                                           List<String> constraints,
                                           TaskRuntimeState taskState) {
        String lower = normalize(input).toLowerCase(Locale.ROOT);
        List<String> missing = new ArrayList<>();
        if (containsAny(lower, "这个", "那个", "it", "this", "that", "再来一次", "继续", "same as before")) {
            missing.add("target_reference");
        }
        if (containsAny(lower, "帮我", "please", "分析", "处理", "solve", "optimize")
                && !containsAny(lower, "怎么", "what", "如何", "目标", "标准", "criteria")) {
            missing.add("success_criteria");
        }
        if (explicitGoal.isBlank()) {
            missing.add("explicit_goal");
        }
        if (entities.isEmpty() && (taskState == TaskRuntimeState.EXECUTING || taskState == TaskRuntimeState.PLANNING)) {
            missing.add("core_entity");
        }
        if (constraints.isEmpty() && containsAny(lower, "必须", "must", "不要", "预算", "deadline", "截止")) {
            missing.add("constraint_detail");
        }
        return missing;
    }

    @SuppressWarnings("unchecked")
    private List<String> inferConstraints(String input, ContextSignals signals, StructuredContextPackage contextPackage) {
        String lower = normalize(input).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (containsAny(lower, "不要", "别", "must", "必须", "only", "仅", "deadline", "截止", "预算", "budget")) {
            out.add("hard_constraint_from_user_input");
        }
        if (!signals.pendingQuestions().isBlank()) {
            out.add("pending_questions_from_state");
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
                                         List<String> missingSlots,
                                         String timeScope,
                                         TaskRuntimeState taskState,
                                         RelationalRuntimeState relationalState,
                                         ContextSignals signals) {
        StringBuilder intent = new StringBuilder();
        intent.append("goal=").append(explicitGoal);
        intent.append("; taskStage=").append(safeName(taskState));
        intent.append("; relationStage=").append(safeName(relationalState));
        intent.append("; timeScope=").append(timeScope);
        intent.append("; entities=").append(entities);
        intent.append("; constraints=").append(constraints);
        if (!missingSlots.isEmpty()) {
            intent.append("; missingSlots=").append(missingSlots);
        }
        if (!signals.lastToolSemantic().isBlank()) {
            intent.append("; latestToolSemantic=").append(signals.lastToolSemantic());
        }
        if (!signals.retrievalIntent().isBlank()) {
            intent.append("; retrievalIntent=").append(signals.retrievalIntent());
        }
        if (!signals.recentDialog().isBlank()) {
            intent.append("; recentDialog=").append(signals.recentDialog());
        }
        if (!input.isBlank()) {
            intent.append("; userFact=").append(input);
        }
        return intent.toString();
    }

    private String buildRagQuery(String explicitGoal,
                                 Map<String, String> entities,
                                 List<String> constraints,
                                 String timeScope,
                                 TaskRuntimeState taskState,
                                 ContextSignals signals) {
        return "goal=" + explicitGoal
                + " | stage=" + safeName(taskState)
                + " | entities=" + entities
                + " | constraints=" + constraints
                + " | timeScope=" + timeScope
                + " | prior_intent=" + signals.retrievalIntent()
                + " | retrieval_target=knowledge,memory,preference";
    }

    private String buildMcpQuery(String explicitGoal,
                                 Map<String, String> entities,
                                 List<String> constraints,
                                 TaskRuntimeState taskState,
                                 ContextSignals signals) {
        return "task_goal=" + explicitGoal
                + " | stage=" + safeName(taskState)
                + " | entities=" + entities
                + " | constraints=" + constraints
                + " | latest_tool=" + signals.lastToolName()
                + " | node=" + signals.currentNode();
    }

    private String buildBlueprintHint(String explicitGoal,
                                      TaskRuntimeState taskState,
                                      List<String> constraints,
                                      String timeScope,
                                      ContextSignals signals) {
        return "stage=" + safeName(taskState)
                + ", goal=" + explicitGoal
                + ", timeScope=" + timeScope
                + ", constraints=" + constraints
                + ", pending=" + signals.pendingQuestions();
    }

    private double scoreConfidence(String input,
                                   List<String> missingSlots,
                                   Map<String, String> entities,
                                   String explicitGoal,
                                   TaskRuntimeState taskState,
                                   ContextSignals signals) {
        double score = 0.40;
        if (!input.isBlank()) {
            score += 0.15;
        }
        if (!explicitGoal.isBlank()) {
            score += 0.15;
        }
        if (!signals.goalFromState().isBlank()) {
            score += 0.08;
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

    private String safeName(Enum<?> value) {
        return value == null ? "UNKNOWN" : value.name();
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private List<String> extractMatches(Pattern pattern, String text, int max) {
        if (pattern == null || text == null || text.isBlank() || max <= 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && out.size() < max) {
            out.add(matcher.group());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private ContextSignals collectSignals(StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return new ContextSignals("", "", "", "", "", "", "", "", "");
        }
        String goalFromState = contextPackage.getTaskStateEntity() == null ? "" : asText(contextPackage.getTaskStateEntity().getObjective());
        String currentNode = contextPackage.getTaskStateEntity() == null ? "" : asText(contextPackage.getTaskStateEntity().getCurrentNode());
        String pendingQuestions = contextPackage.getTaskStateEntity() == null ? "" : asText(contextPackage.getTaskStateEntity().getPendingQuestions());
        String lastToolName = contextPackage.getToolState() == null ? "" : asText(contextPackage.getToolState().getLastToolName());
        String lastToolSemantic = contextPackage.getToolState() == null ? "" : asText(contextPackage.getToolState().getLastToolSemanticSummary());
        String retrievalIntent = contextPackage.getRetrievalState() == null ? "" : asText(contextPackage.getRetrievalState().getReconstructedIntent());
        String lastSummary = contextPackage.getContextState() == null ? "" : asText(contextPackage.getContextState().getLatestNarrativeSummary());
        String latestTimeScope = "";
        Map<String, Object> latestSnapshot = contextPackage.getContextState() == null ? Collections.emptyMap() : contextPackage.getContextState().getLatestStateSnapshot();
        if (latestSnapshot != null && !latestSnapshot.isEmpty()) {
            latestTimeScope = asText(latestSnapshot.get("timeScope"));
        }
        if (latestTimeScope.isBlank() && contextPackage.getTaskContext() != null) {
            Object working = contextPackage.getTaskContext().get("working_memory");
            if (working instanceof Map<?, ?> map) {
                latestTimeScope = asText(((Map<String, Object>) map).get("time_scope"));
            }
        }
        String recentDialog = "";
        if (contextPackage.getRecentMessages() != null && !contextPackage.getRecentMessages().isEmpty()) {
            List<Map<String, Object>> messages = contextPackage.getRecentMessages();
            int from = Math.max(0, messages.size() - 20);
            StringBuilder buffer = new StringBuilder();
            for (Map<String, Object> message : messages.subList(from, messages.size())) {
                buffer.append(asText(message.get("role"))).append(':').append(asText(message.get("content_text"))).append('|');
            }
            recentDialog = buffer.toString();
        }
        return new ContextSignals(goalFromState, currentNode, pendingQuestions, lastToolName, lastToolSemantic, retrievalIntent, lastSummary, latestTimeScope, recentDialog);
    }

    private record ContextSignals(String goalFromState,
                                  String currentNode,
                                  String pendingQuestions,
                                  String lastToolName,
                                  String lastToolSemantic,
                                  String retrievalIntent,
                                  String lastSummary,
                                  String latestTimeScope,
                                  String recentDialog) {
    }
}
