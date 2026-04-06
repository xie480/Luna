package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.constants.JsonFieldConstants;
import org.yilena.luna.context.ToolSemanticAgent;
import org.yilena.luna.context.ToolSemanticSchemaProvider;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.enums.ToolStatusEnum;
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

    private static final String TOOL_STATUS_PROMPT_VALUES = String.join("|", ToolStatusEnum.codes());

    private static final String TOOL_SEMANTIC_PROMPT = """
            You are Tool Semantic Agent.
            Convert raw tool output into strict JSON:
            {
              "toolName":"...",
              "toolDescription":"...",
              "toolStatus":"%s",
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
            """.formatted(TOOL_STATUS_PROMPT_VALUES);

    private final ObjectMapper objectMapper;
    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final ToolSemanticSchemaProvider schemaProvider;

    @Override
    public ToolSemanticResult translate(String toolName,
                                        String toolDescription,
                                        String rawResult,
                                        TaskRuntimeState taskState,
                                        String currentNodeGoal) {
        List<String> errors = new ArrayList<>();
        for (int attempt = 1; attempt <= 3; attempt++) {
            ToolSemanticResult llmResult = tryModelTranslation(
                    toolName,
                    toolDescription,
                    rawResult,
                    taskState,
                    currentNodeGoal,
                    attempt,
                    errors
            );
            if (llmResult != null) {
                return llmResult;
            }
        }
        throw new IllegalStateException("tool semantic translation failed after retries: tool="
                + safe(toolName)
                + ", errors="
                + errors);
    }

    private ToolSemanticResult tryModelTranslation(String toolName,
                                                   String toolDescription,
                                                   String rawResult,
                                                   TaskRuntimeState taskState,
                                                   String currentNodeGoal,
                                                   int attempt,
                                                   List<String> errors) {
        try {
            String prompt = TOOL_SEMANTIC_PROMPT.formatted(
                    safe(toolName),
                    safe(toolDescription),
                    taskState == null ? "UNKNOWN" : taskState.name(),
                    currentNodeGoal == null ? "" : currentNodeGoal,
                    rawResult == null ? "" : rawResult
            ) + "\nretryAttempt=" + attempt
                    + "\njsonSchema=" + schemaProvider.toolSemanticSchema()
                    + "\nOutput strict JSON only.";
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
                errors.add("attempt_" + attempt + ":empty_content");
                return null;
            }
            JsonNode node = parse(stripFence(content));
            if (node == null || !node.isObject() || node.size() == 0) {
                errors.add("attempt_" + attempt + ":invalid_json");
                return null;
            }
            String status = normalizeStatus(node.path("toolStatus").asText(node.path(JsonFieldConstants.STATUS).asText(ToolStatusEnum.UNKNOWN.getCode())));
            List<String> keyFacts = readStringArray(node.path("keyFacts"));
            List<String> unresolved = readStringArray(node.path("unresolvedIssues"));
            String impact = node.path("businessImpact").asText("");
            String nextStepHint = node.path("nextStepHint").asText("");
            double confidence = node.path("confidence").asDouble(0.0);
            confidence = confidence <= 0 ? 0.20 : Math.max(0.15, Math.min(confidence, 0.99));
            if (keyFacts.isEmpty() || impact.isBlank() || nextStepHint.isBlank()) {
                errors.add("attempt_" + attempt + ":missing_required_fields");
                return null;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(JsonFieldConstants.STATUS, status);
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
        } catch (Exception ex) {
            errors.add("attempt_" + attempt + ":" + ex.getClass().getSimpleName());
            return null;
        }
    }

    private JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception ex) {
            return null;
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
        return ToolStatusEnum.fromRaw(raw).getCode();
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
