package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.InputReconstructionAgent;
import org.yilena.luna.context.Lexicon;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.utils.LlmClientUtil;

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
@RequiredArgsConstructor
public class DefaultInputReconstructionAgent implements InputReconstructionAgent {

    private static final Pattern DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern QUARTER_PATTERN = Pattern.compile("\\bq([1-4])\\b", Pattern.CASE_INSENSITIVE);
    private static final String RECONSTRUCTION_PROMPT = """
            You are Input Reconstruction Agent.
            Return one compact JSON only. No markdown.
            Keep semantic fidelity with current task state and short-term memory.
            Required JSON fields:
            normalizedUserIntent(string), explicitTaskGoal(string), clarifiedEntities(object<string,string>),
            missingSlots(array<string>), timeScope(string), businessConstraints(array<string>),
            reformulatedQueryForRag(string), reformulatedQueryForMcp(string), blueprintHint(string), intentConfidence(number 0..1)

            Inputs:
            sessionId=%s
            taskState=%s
            relationalState=%s
            userInput=%s
            goalFromState=%s
            currentNode=%s
            pendingQuestions=%s
            latestTool=%s
            latestToolSemantic=%s
            retrievalIntent=%s
            latestNarrativeSummary=%s
            recentDialog=%s
            latestTimeScope=%s
            lastNextActionHint=%s
            latestStateSnapshotDigest=%s
            unfinishedActions=%s
            """;

    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final ObjectMapper objectMapper;

    @Override
    public InputReconstructionResult reconstruct(String sessionId,
                                                 String userInput,
                                                 StructuredContextPackage contextPackage,
                                                 TaskRuntimeState taskState,
                                                 RelationalRuntimeState relationalState) {
        ContextSignals signals = collectSignals(contextPackage);
        InputReconstructionResult modelResult = tryModelReconstruction(sessionId, userInput, taskState, relationalState, signals);
        if (modelResult != null) {
            return modelResult;
        }
        String input = normalize(userInput);
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

    private InputReconstructionResult tryModelReconstruction(String sessionId,
                                                             String userInput,
                                                             TaskRuntimeState taskState,
                                                             RelationalRuntimeState relationalState,
                                                             ContextSignals signals) {
        try {
            String prompt = RECONSTRUCTION_PROMPT.formatted(
                    normalize(sessionId),
                    safeName(taskState),
                    safeName(relationalState),
                    normalize(userInput),
                    signals.goalFromState(),
                    signals.currentNode(),
                    signals.pendingQuestions(),
                    signals.lastToolName(),
                    signals.lastToolSemantic(),
                    signals.retrievalIntent(),
                    signals.lastSummary(),
                    signals.recentDialog(),
                    signals.latestTimeScope(),
                    signals.lastNextActionHint(),
                    signals.latestStateSnapshotDigest(),
                    signals.unfinishedActions()
            );
            LlmRequest request = LlmRequest.builder()
                    .modelType(ModelType.OPENAI_COMPATIBLE)
                    .modelName(resolveSmallAgentModel())
                    .messages(List.of(LlmMessage.user(prompt)))
                    .temperature(0.1)
                    .enablePromptInjectionCheck(false)
                    .build();
            LlmResponse response = llmClientUtil.generate(request);
            String content = response == null ? "" : normalize(response.getContent());
            if (content.isBlank()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(stripFence(content));
            String explicitTaskGoal = asText(node.path("explicitTaskGoal").asText(""));
            if (explicitTaskGoal.isBlank()) {
                return null;
            }
            double confidence = node.path("intentConfidence").asDouble(0.0);
            confidence = confidence <= 0 ? 0.72 : Math.max(0.20, Math.min(confidence, 0.99));
            return InputReconstructionResult.builder()
                    .normalizedUserIntent(asText(node.path("normalizedUserIntent").asText("")))
                    .explicitTaskGoal(explicitTaskGoal)
                    .clarifiedEntities(toStringMap(node.path("clarifiedEntities")))
                    .missingSlots(toStringList(node.path("missingSlots")))
                    .timeScope(asText(node.path("timeScope").asText("unspecified")))
                    .businessConstraints(toStringList(node.path("businessConstraints")))
                    .reformulatedQueryForRag(asText(node.path("reformulatedQueryForRag").asText(explicitTaskGoal)))
                    .reformulatedQueryForMcp(asText(node.path("reformulatedQueryForMcp").asText(explicitTaskGoal)))
                    .blueprintHint(asText(node.path("blueprintHint").asText(explicitTaskGoal)))
                    .intentConfidence(confidence)
                    .build();
        } catch (Exception e) {
            log.debug("input reconstruction model path failed, fallback to heuristic: {}", e.getMessage());
            return null;
        }
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
        if (containsAny(lower, Lexicon.TIME_SCOPE_TODAY_KEYWORDS)) {
            return "today";
        }
        if (containsAny(lower, Lexicon.TIME_SCOPE_TOMORROW_KEYWORDS)) {
            return "tomorrow";
        }
        if (containsAny(lower, Lexicon.TIME_SCOPE_THIS_WEEK_KEYWORDS)) {
            return "this_week";
        }
        if (containsAny(lower, Lexicon.TIME_SCOPE_THIS_MONTH_KEYWORDS)) {
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
        if (containsAny(lower, Lexicon.TARGET_REFERENCE_KEYWORDS)) {
            missing.add("target_reference");
        }
        if (containsAny(lower, Lexicon.TASK_ACTION_KEYWORDS)
                && !containsAny(lower, Lexicon.GOAL_QUERY_KEYWORDS)) {
            missing.add("success_criteria");
        }
        if (explicitGoal.isBlank()) {
            missing.add("explicit_goal");
        }
        if (entities.isEmpty() && (taskState == TaskRuntimeState.EXECUTING || taskState == TaskRuntimeState.PLANNING)) {
            missing.add("core_entity");
        }
        if (constraints.isEmpty() && containsAny(lower, Lexicon.CONSTRAINT_TRIGGER_KEYWORDS)) {
            missing.add("constraint_detail");
        }
        return missing;
    }

    @SuppressWarnings("unchecked")
    private List<String> inferConstraints(String input, ContextSignals signals, StructuredContextPackage contextPackage) {
        String lower = normalize(input).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (containsAny(lower, Lexicon.HARD_CONSTRAINT_KEYWORDS)) {
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
        if (!signals.lastNextActionHint().isBlank()) {
            intent.append("; lastNextActionHint=").append(signals.lastNextActionHint());
        }
        if (!signals.latestStateSnapshotDigest().isBlank()) {
            intent.append("; latestStateSnapshotDigest=").append(signals.latestStateSnapshotDigest());
        }
        if (!signals.unfinishedActions().isBlank()) {
            intent.append("; unfinishedActions=").append(signals.unfinishedActions());
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
                + " | node=" + signals.currentNode()
                + " | unfinished_actions=" + signals.unfinishedActions();
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
                + ", pending=" + signals.pendingQuestions()
                + ", nextActionHint=" + signals.lastNextActionHint();
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

    private String stripFence(String text) {
        String value = normalize(text);
        if (value.startsWith("```")) {
            value = value.replaceAll("(?s)^```[a-zA-Z]*\\s*", "");
            value = value.replaceAll("(?s)```\\s*$", "");
        }
        return value.trim();
    }

    private String resolveSmallAgentModel() {
        if (geminiProperty != null && geminiProperty.getChat() != null && geminiProperty.getChat().getModelName() != null
                && !geminiProperty.getChat().getModelName().isBlank()) {
            return geminiProperty.getChat().getModelName();
        }
        if (geminiProperty != null && geminiProperty.getBig() != null && geminiProperty.getBig().getModelName() != null
                && !geminiProperty.getBig().getModelName().isBlank()) {
            return geminiProperty.getBig().getModelName();
        }
        return geminiProperty.getFlash().getModelName();
    }

    private Map<String, String> toStringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> out.put(entry.getKey(), asText(entry.getValue().asText(""))));
        return out;
    }

    private List<String> toStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> {
            String value = asText(item.asText(""));
            if (!value.isBlank()) {
                out.add(value);
            }
        });
        return out;
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
            return new ContextSignals("", "", "", "", "", "", "", "", "", "", "", "");
        }
        String goalFromState = contextPackage.getTaskStateEntity() == null ? "" : asText(contextPackage.getTaskStateEntity().getObjective());
        String currentNode = contextPackage.getTaskStateEntity() == null ? "" : asText(contextPackage.getTaskStateEntity().getCurrentNode());
        String pendingQuestions = contextPackage.getTaskStateEntity() == null ? "" : asText(contextPackage.getTaskStateEntity().getPendingQuestions());
        String lastNextActionHint = contextPackage.getTaskStateEntity() == null ? "" : asText(contextPackage.getTaskStateEntity().getNextActionHint());
        String lastToolName = contextPackage.getToolState() == null ? "" : asText(contextPackage.getToolState().getLastToolName());
        String lastToolSemantic = contextPackage.getToolState() == null ? "" : asText(contextPackage.getToolState().getLastToolSemanticSummary());
        String retrievalIntent = contextPackage.getRetrievalState() == null ? "" : asText(contextPackage.getRetrievalState().getReconstructedIntent());
        String lastSummary = contextPackage.getContextState() == null ? "" : asText(contextPackage.getContextState().getLatestNarrativeSummary());
        String latestTimeScope = "";
        String latestStateSnapshotDigest = "";
        String unfinishedActions = "";
        Map<String, Object> latestSnapshot = contextPackage.getContextState() == null ? Collections.emptyMap() : contextPackage.getContextState().getLatestStateSnapshot();
        if (latestSnapshot != null && !latestSnapshot.isEmpty()) {
            latestTimeScope = asText(latestSnapshot.get("timeScope"));
            latestStateSnapshotDigest = buildStateSnapshotDigest(latestSnapshot);
            unfinishedActions = extractUnfinishedActions(latestSnapshot);
        }
        if (latestTimeScope.isBlank() && contextPackage.getTaskContext() != null) {
            Object working = contextPackage.getTaskContext().get("working_memory");
            if (working instanceof Map<?, ?> map) {
                latestTimeScope = asText(((Map<String, Object>) map).get("time_scope"));
            }
        }
        if (unfinishedActions.isBlank() && contextPackage.getTaskStateEntity() != null) {
            List<String> unfinished = new ArrayList<>();
            if (contextPackage.getTaskStateEntity().getPendingQuestions() != null) {
                unfinished.addAll(contextPackage.getTaskStateEntity().getPendingQuestions());
            }
            if (contextPackage.getTaskStateEntity().getFailedSteps() != null) {
                unfinished.addAll(contextPackage.getTaskStateEntity().getFailedSteps());
            }
            unfinishedActions = unfinished.stream()
                    .filter(item -> item != null && !item.isBlank())
                    .distinct()
                    .limit(12)
                    .toList()
                    .toString();
        }
        String recentDialog = "";
        if (contextPackage.getRecentMessages() != null && !contextPackage.getRecentMessages().isEmpty()) {
            List<Map<String, Object>> messages = contextPackage.getRecentMessages();
            StringBuilder buffer = new StringBuilder();
            for (Map<String, Object> message : messages) {
                buffer.append(asText(message.get("role"))).append(':').append(asText(message.get("content_text"))).append('|');
            }
            recentDialog = buffer.toString();
        }
        return new ContextSignals(
                goalFromState,
                currentNode,
                pendingQuestions,
                lastToolName,
                lastToolSemantic,
                retrievalIntent,
                lastSummary,
                latestTimeScope,
                recentDialog,
                lastNextActionHint,
                latestStateSnapshotDigest,
                unfinishedActions
        );
    }

    private record ContextSignals(String goalFromState,
                                  String currentNode,
                                  String pendingQuestions,
                                  String lastToolName,
                                  String lastToolSemantic,
                                  String retrievalIntent,
                                  String lastSummary,
                                  String latestTimeScope,
                                  String recentDialog,
                                  String lastNextActionHint,
                                  String latestStateSnapshotDigest,
                                  String unfinishedActions) {
    }

    private String buildStateSnapshotDigest(Map<String, Object> latestSnapshot) {
        if (latestSnapshot == null || latestSnapshot.isEmpty()) {
            return "";
        }
        String stage = asText(latestSnapshot.get("currentStage"));
        String nextStep = asText(latestSnapshot.get("nextStep"));
        String unresolved = asText(latestSnapshot.get("unresolvedIssues"));
        String latestTool = asText(latestSnapshot.get("latestToolConclusion"));
        return "stage=" + stage + ";nextStep=" + nextStep + ";unresolved=" + unresolved + ";latestTool=" + latestTool;
    }

    @SuppressWarnings("unchecked")
    private String extractUnfinishedActions(Map<String, Object> latestSnapshot) {
        if (latestSnapshot == null || latestSnapshot.isEmpty()) {
            return "";
        }
        Object unresolved = latestSnapshot.get("unresolvedIssues");
        if (unresolved instanceof List<?> list) {
            return list.stream()
                    .map(this::asText)
                    .filter(item -> item != null && !item.isBlank())
                    .distinct()
                    .limit(12)
                    .toList()
                    .toString();
        }
        String fallback = asText(unresolved);
        if (!fallback.isBlank()) {
            return fallback;
        }
        fallback = asText(latestSnapshot.get("unfinishedActions"));
        return fallback;
    }
}
