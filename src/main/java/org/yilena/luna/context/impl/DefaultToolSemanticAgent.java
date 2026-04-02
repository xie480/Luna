package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.ToolSemanticAgent;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DefaultToolSemanticAgent implements ToolSemanticAgent {

    private static final String TOOL_SEMANTIC_PROMPT = """
            You are Tool Semantic Agent.
            Convert raw tool output into strict JSON:
            {
              "toolStatus":"SUCCESS|PENDING|FAILED|UNKNOWN",
              "keyFacts":["..."],
              "businessImpact":"...",
              "unresolvedIssues":["..."],
              "nextStepHint":"...",
              "confidence":0.0
            }
            Keep faithful to raw output, no hallucination.
            taskState=%s
            currentNodeGoal=%s
            rawToolResult=%s
            """;

    private final ObjectMapper objectMapper;
    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;

    @Override
    public ToolSemanticResult translate(String toolContext, TaskRuntimeState taskState, String currentNodeGoal) {
        ToolSemanticResult llmResult = tryModelTranslation(toolContext, taskState, currentNodeGoal);
        if (llmResult != null) {
            return llmResult;
        }
        JsonNode node = parse(toolContext);
        String status = normalizeStatus(node.path("status").asText(""));
        List<String> keyFacts = buildKeyFacts(node);
        List<String> unresolved = buildUnresolved(status, node);
        String businessImpact = buildBusinessImpact(status, taskState, currentNodeGoal);
        String nextStepHint = buildNextStepHint(status, unresolved, taskState);
        double confidence = computeConfidence(status, keyFacts, unresolved);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("keyFacts", keyFacts);
        payload.put("businessImpact", businessImpact);
        payload.put("unresolvedIssues", unresolved);
        payload.put("nextStepHint", nextStepHint);
        payload.put("confidence", confidence);

        return ToolSemanticResult.builder()
                .toolStatus(status)
                .keyFacts(keyFacts)
                .businessImpact(businessImpact)
                .unresolvedIssues(unresolved)
                .nextStepHint(nextStepHint)
                .confidence(confidence)
                .semanticPayload(payload)
                .build();
    }

    private ToolSemanticResult tryModelTranslation(String toolContext, TaskRuntimeState taskState, String currentNodeGoal) {
        try {
            String prompt = TOOL_SEMANTIC_PROMPT.formatted(
                    taskState == null ? "UNKNOWN" : taskState.name(),
                    currentNodeGoal == null ? "" : currentNodeGoal,
                    toolContext == null ? "" : toolContext
            );
            LlmRequest request = LlmRequest.builder()
                    .modelType(ModelType.OPENAI_COMPATIBLE)
                    .modelName(resolveSmallAgentModel())
                    .messages(List.of(LlmMessage.user(prompt)))
                    .temperature(0.1)
                    .enablePromptInjectionCheck(false)
                    .build();
            LlmResponse response = llmClientUtil.generate(request);
            String content = response == null ? "" : response.getContent();
            if (content == null || content.isBlank()) {
                return null;
            }
            JsonNode node = parse(stripFence(content));
            String status = normalizeStatus(node.path("toolStatus").asText(node.path("status").asText("UNKNOWN")));
            List<String> keyFacts = readStringArray(node.path("keyFacts"));
            List<String> unresolved = readStringArray(node.path("unresolvedIssues"));
            String impact = node.path("businessImpact").asText("");
            String nextStepHint = node.path("nextStepHint").asText("");
            double confidence = node.path("confidence").asDouble(0.0);
            confidence = confidence <= 0 ? computeConfidence(status, keyFacts, unresolved) : Math.max(0.15, Math.min(confidence, 0.99));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", status);
            payload.put("keyFacts", keyFacts);
            payload.put("businessImpact", impact);
            payload.put("unresolvedIssues", unresolved);
            payload.put("nextStepHint", nextStepHint);
            payload.put("confidence", confidence);

            return ToolSemanticResult.builder()
                    .toolStatus(status)
                    .keyFacts(keyFacts)
                    .businessImpact(impact)
                    .unresolvedIssues(unresolved)
                    .nextStepHint(nextStepHint)
                    .confidence(confidence)
                    .semanticPayload(payload)
                    .build();
        } catch (Exception ignore) {
            return null;
        }
    }

    private JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignore) {
            return objectMapper.createObjectNode();
        }
    }

    private String stripFence(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("```")) {
            value = value.replaceAll("(?s)^```[a-zA-Z]*\\s*", "");
            value = value.replaceAll("(?s)```\\s*$", "");
        }
        return value.trim();
    }

    private String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        String upper = raw.trim().toUpperCase();
        if ("SUCCESS".equals(upper) || "OK".equals(upper)) {
            return "SUCCESS";
        }
        if ("PENDING".equals(upper) || "RUNNING".equals(upper)) {
            return "PENDING";
        }
        if ("FAILED".equals(upper) || "ERROR".equals(upper)) {
            return "FAILED";
        }
        return upper;
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

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("");
            if (!value.isBlank()) {
                out.add(value);
            }
        });
        return out;
    }

    private List<String> buildKeyFacts(JsonNode node) {
        List<String> facts = new ArrayList<>();
        if (node.has("tool")) {
            facts.add("tool=" + node.path("tool").asText(""));
        }
        if (node.has("taskId")) {
            facts.add("task_id=" + node.path("taskId").asText(""));
        }
        if (node.has("workflowName")) {
            facts.add("workflow=" + node.path("workflowName").asText(""));
        }
        if (node.has("message")) {
            String msg = node.path("message").asText("");
            if (!msg.isBlank()) {
                facts.add("message=" + msg);
            }
        }
        if (facts.isEmpty() && !node.isMissingNode()) {
            facts.add("raw_result_available");
        }
        return facts;
    }

    private List<String> buildUnresolved(String status, JsonNode node) {
        List<String> unresolved = new ArrayList<>();
        if ("FAILED".equals(status)) {
            String err = node.path("error").asText(node.path("message").asText(""));
            unresolved.add(err.isBlank() ? "tool_execution_failed" : err);
        }
        if ("PENDING".equals(status)) {
            unresolved.add("tool_execution_pending");
        }
        return unresolved;
    }

    private String buildBusinessImpact(String status, TaskRuntimeState taskState, String currentNodeGoal) {
        String goal = currentNodeGoal == null || currentNodeGoal.isBlank() ? "current node objective" : currentNodeGoal;
        if ("SUCCESS".equals(status)) {
            return "Tool output is ready and can be used to progress " + goal + ".";
        }
        if ("PENDING".equals(status)) {
            return "Tool execution is pending; keep user informed and avoid conflicting calls.";
        }
        if ("FAILED".equals(status)) {
            return "Tool execution failed; evaluate fallback path, retry, or replan.";
        }
        if (taskState == TaskRuntimeState.REPORTING) {
            return "Tool status is unclear, use conservative interpretation in report.";
        }
        return "Tool status is unclear; avoid over-committing downstream decisions.";
    }

    private String buildNextStepHint(String status, List<String> unresolved, TaskRuntimeState taskState) {
        if ("SUCCESS".equals(status)) {
            return "inject semantic facts into context and continue execution";
        }
        if ("PENDING".equals(status)) {
            return "return pending response and await callback";
        }
        if ("FAILED".equals(status)) {
            return taskState == TaskRuntimeState.REPLANNING
                    ? "trigger replan with failure reason"
                    : "attempt parameter repair or switch capability";
        }
        if (!unresolved.isEmpty()) {
            return "request clarification or validate tool payload";
        }
        return "preserve raw output and continue with guarded reasoning";
    }

    private double computeConfidence(String status, List<String> keyFacts, List<String> unresolved) {
        double confidence = 0.55;
        if ("SUCCESS".equals(status)) {
            confidence += 0.25;
        } else if ("PENDING".equals(status)) {
            confidence += 0.08;
        } else if ("FAILED".equals(status)) {
            confidence -= 0.20;
        }
        confidence += Math.min(0.12, keyFacts.size() * 0.03);
        confidence -= Math.min(0.20, unresolved.size() * 0.06);
        if (confidence < 0.15) {
            return 0.15;
        }
        return Math.min(confidence, 0.98);
    }
}
