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
              "toolName":"...",
              "toolDescription":"...",
              "toolStatus":"SUCCESS|PENDING|FAILED|UNKNOWN",
              "keyFacts":["..."],
              "businessImpact":"...",
              "unresolvedIssues":["..."],
              "nextStepHint":"...",
              "confidence":0.0
            }
            Keep faithful to raw output, no hallucination.
            toolName=%s
            toolDescription=%s
            taskState=%s
            currentNodeGoal=%s
            rawResult=%s
            """;

    private final ObjectMapper objectMapper;
    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;

    @Override
    public ToolSemanticResult translate(String toolName,
                                        String toolDescription,
                                        String rawResult,
                                        TaskRuntimeState taskState,
                                        String currentNodeGoal) {
        ToolSemanticResult llmResult = tryModelTranslation(toolName, toolDescription, rawResult, taskState, currentNodeGoal);
        if (llmResult != null) {
            return llmResult;
        }
        return buildConservativeFallback(toolName, toolDescription, rawResult, taskState, currentNodeGoal);
    }

    private ToolSemanticResult tryModelTranslation(String toolName,
                                                   String toolDescription,
                                                   String rawResult,
                                                   TaskRuntimeState taskState,
                                                   String currentNodeGoal) {
        try {
            String prompt = TOOL_SEMANTIC_PROMPT.formatted(
                    safe(toolName),
                    safe(toolDescription),
                    taskState == null ? "UNKNOWN" : taskState.name(),
                    currentNodeGoal == null ? "" : currentNodeGoal,
                    rawResult == null ? "" : rawResult
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
                    .toolName(node.path("toolName").asText(safe(toolName)))
                    .toolDescription(node.path("toolDescription").asText(safe(toolDescription)))
                    .rawResultDigest(compactContent(safe(rawResult), 800))
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

    private ToolSemanticResult buildConservativeFallback(String toolName,
                                                         String toolDescription,
                                                         String rawResult,
                                                         TaskRuntimeState taskState,
                                                         String currentNodeGoal) {
        String status = "UNKNOWN";
        List<String> keyFacts = new ArrayList<>();
        if (rawResult == null || rawResult.isBlank()) {
            keyFacts.add("raw_result_missing");
        } else {
            keyFacts.add("raw_result_available");
            keyFacts.add("raw_result_digest=" + compactContent(rawResult, 180));
        }
        List<String> unresolved = List.of("tool_semantic_model_unavailable");
        String businessImpact = "Tool semantic interpretation fallback engaged; keep downstream reasoning conservative.";
        if (currentNodeGoal != null && !currentNodeGoal.isBlank()) {
            businessImpact = businessImpact + " Current node goal=" + compactContent(currentNodeGoal, 120) + ".";
        }
        if (taskState != null) {
            businessImpact = businessImpact + " Task stage=" + taskState.name() + ".";
        }
        String nextStepHint = "preserve raw tool output and continue with guarded decision path";
        double confidence = 0.30;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("keyFacts", keyFacts);
        payload.put("businessImpact", businessImpact);
        payload.put("unresolvedIssues", unresolved);
        payload.put("nextStepHint", nextStepHint);
        payload.put("confidence", confidence);
        payload.put("fallback", "small_agent_unavailable");

        return ToolSemanticResult.builder()
                .toolName(safe(toolName))
                .toolDescription(safe(toolDescription))
                .rawResultDigest(compactContent(safe(rawResult), 800))
                .toolStatus(status)
                .keyFacts(keyFacts)
                .businessImpact(businessImpact)
                .unresolvedIssues(unresolved)
                .nextStepHint(nextStepHint)
                .confidence(confidence)
                .semanticPayload(payload)
                .build();
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

    private String compactContent(String content, int maxLen) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
