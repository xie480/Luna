package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.SummaryAgent;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DefaultSummaryAgent implements SummaryAgent {

    private static final String SUMMARY_PROMPT = """
            You are Summary Agent.
            Return JSON only:
            {
              "narrativeSummary":"...",
              "stateSnapshot":{
                "currentStage":"...",
                "confirmedSlots":["..."],
                "finishedSteps":["..."],
                "pendingIssues":["..."],
                "latestToolConclusion":"...",
                "currentConstraints":["..."],
                "nextStep":"..."
              }
            }
            Keep semantic fidelity. Do not drop unresolved issues.
            userInput=%s
            assistantReply=%s
            taskState=%s
            relationalState=%s
            shortTermMemorySize=%s
            shortTermMemoryDigest=%s
            latestToolName=%s
            latestToolStatus=%s
            pendingQuestions=%s
            """;

    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final ObjectMapper objectMapper;

    @Override
    public SummaryResult summarize(String userInput, String assistantReply, StructuredContextPackage contextPackage) {
        SummaryResult llmSummary = tryModelSummary(userInput, assistantReply, contextPackage);
        if (llmSummary != null) {
            return llmSummary;
        }
        String narrative = buildNarrative(userInput, assistantReply, contextPackage);
        Map<String, Object> snapshot = buildStateSnapshot(contextPackage);
        return SummaryResult.builder()
                .narrativeSummary(narrative)
                .stateSnapshot(snapshot)
                .build();
    }

    private SummaryResult tryModelSummary(String userInput, String assistantReply, StructuredContextPackage contextPackage) {
        try {
            String prompt = SUMMARY_PROMPT.formatted(
                    safe(userInput),
                    safe(assistantReply),
                    contextPackage == null || contextPackage.getTaskState() == null ? "UNKNOWN" : contextPackage.getTaskState().name(),
                    contextPackage == null || contextPackage.getRelationalState() == null ? "UNKNOWN" : contextPackage.getRelationalState().name(),
                    contextPackage == null || contextPackage.getRecentMessages() == null ? 0 : contextPackage.getRecentMessages().size(),
                    buildShortTermMemoryDigest(contextPackage),
                    contextPackage == null || contextPackage.getToolState() == null ? "" : safe(contextPackage.getToolState().getLastToolName()),
                    contextPackage == null || contextPackage.getToolState() == null ? "" : safe(contextPackage.getToolState().getLastToolStatus()),
                    contextPackage == null || contextPackage.getTaskStateEntity() == null ? "" : safe(contextPackage.getTaskStateEntity().getPendingQuestions())
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
            JsonNode node = objectMapper.readTree(stripFence(content));
            String narrative = node.path("narrativeSummary").asText("");
            if (narrative.isBlank()) {
                return null;
            }
            Map<String, Object> snapshot = node.path("stateSnapshot").isObject()
                    ? objectMapper.convertValue(node.path("stateSnapshot"), Map.class)
                    : Map.of();
            if (snapshot.isEmpty()) {
                snapshot = buildStateSnapshot(contextPackage);
            }
            return SummaryResult.builder()
                    .narrativeSummary(narrative)
                    .stateSnapshot(snapshot)
                    .build();
        } catch (Exception ignore) {
            return null;
        }
    }

    private String buildNarrative(String userInput, String assistantReply, StructuredContextPackage contextPackage) {
        StringBuilder sb = new StringBuilder(320);
        sb.append("User intent: ").append(safe(userInput)).append(". ");
        if (contextPackage != null) {
            sb.append("Task state=").append(contextPackage.getTaskState()).append(", relational state=")
                    .append(contextPackage.getRelationalState()).append(". ");
            List<Map<String, Object>> recent = contextPackage.getRecentMessages();
            if (recent != null && !recent.isEmpty()) {
                int total = recent.size();
                Map<String, Long> roleCounts = recent.stream()
                        .collect(Collectors.groupingBy(row -> safe(row.get("role")), LinkedHashMap::new, Collectors.counting()));
                sb.append("Short-term memory size=").append(total).append(", full-memory role distribution=").append(roleCounts).append(". ");
                sb.append("Interaction digest=").append(buildShortTermMemoryDigest(contextPackage)).append(". ");
            }
            if (contextPackage.getTaskStateEntity() != null) {
                sb.append("Task objective=").append(safe(contextPackage.getTaskStateEntity().getObjective())).append("; ");
                sb.append("Pending questions=").append(safe(contextPackage.getTaskStateEntity().getPendingQuestions())).append("; ");
            }
            if (contextPackage.getToolState() != null) {
                sb.append("Latest tool=").append(safe(contextPackage.getToolState().getLastToolName()))
                        .append(", status=").append(safe(contextPackage.getToolState().getLastToolStatus())).append(". ");
            }
        }
        sb.append("Assistant response delivered: ").append(safe(assistantReply));
        return sb.toString().trim();
    }

    private String buildShortTermMemoryDigest(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRecentMessages() == null || contextPackage.getRecentMessages().isEmpty()) {
            return "";
        }
        List<Map<String, Object>> recentMessages = contextPackage.getRecentMessages();
        StringBuilder digest = new StringBuilder(2048);
        final int maxChars = 3200;
        for (Map<String, Object> row : recentMessages) {
            String role = safe(row.get("role"));
            String content = compactContent(safe(row.get("content_text")), 180);
            if (role.isBlank() && content.isBlank()) {
                continue;
            }
            String line = "[" + role + "] " + content;
            if (digest.length() + line.length() + 3 > maxChars) {
                digest.append(" ... [semantic_compacted_from_total=").append(recentMessages.size()).append("]");
                break;
            }
            digest.append(line).append(" | ");
        }
        return digest.toString().trim();
    }

    private String compactContent(String content, int maxLen) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        StringBuilder compacted = new StringBuilder();
        for (String word : normalized.split("\\s+")) {
            if (word == null || word.isBlank()) {
                continue;
            }
            if (compacted.length() == 0) {
                if (word.length() <= maxLen) {
                    compacted.append(word);
                }
                continue;
            }
            if (compacted.length() + 1 + word.length() > maxLen) {
                break;
            }
            compacted.append(' ').append(word);
        }
        return compacted.toString();
    }

    private Map<String, Object> buildStateSnapshot(StructuredContextPackage contextPackage) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (contextPackage == null) {
            snapshot.put("taskState", "UNKNOWN");
            snapshot.put("relationalState", "UNKNOWN");
            snapshot.put("nextStep", "continue");
            return snapshot;
        }
        snapshot.put("taskState", contextPackage.getTaskState() == null ? "UNKNOWN" : contextPackage.getTaskState().name());
        snapshot.put("relationalState", contextPackage.getRelationalState() == null ? "UNKNOWN" : contextPackage.getRelationalState().name());
        snapshot.put("shortTermMemorySize", contextPackage.getRecentMessages() == null ? 0 : contextPackage.getRecentMessages().size());
        snapshot.put("tokenBudgetPlan", contextPackage.getTokenBudgetPlan() == null ? Map.of() : contextPackage.getTokenBudgetPlan());
        snapshot.put("activeCapabilities", contextPackage.getCapabilityCandidates() == null ? 0 : contextPackage.getCapabilityCandidates().size());
        snapshot.put("taskStateEntity", contextPackage.getTaskStateEntity() == null ? Map.of() : contextPackage.getTaskStateEntity());
        snapshot.put("retrievalState", contextPackage.getRetrievalState() == null ? Map.of() : contextPackage.getRetrievalState());
        snapshot.put("toolState", contextPackage.getToolState() == null ? Map.of() : contextPackage.getToolState());
        snapshot.put("contextState", contextPackage.getContextState() == null ? Map.of() : contextPackage.getContextState());
        snapshot.put("nextStep", inferNextStep(contextPackage));
        return snapshot;
    }

    private String inferNextStep(StructuredContextPackage contextPackage) {
        if (contextPackage.getTaskState() == null) {
            return "continue";
        }
        return switch (contextPackage.getTaskState()) {
            case PLANNING, REPLANNING -> "build_or_update_plan";
            case EXECUTING -> "execute_or_call_tool";
            case WAITING_APPROVAL -> "wait_approval";
            case WAITING_TOOL -> "wait_tool_result";
            case REPORTING -> "finalize_report";
            default -> "continue_dialog";
        };
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
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

    private String stripFence(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("```")) {
            value = value.replaceAll("(?s)^```[a-zA-Z]*\\s*", "");
            value = value.replaceAll("(?s)```\\s*$", "");
        }
        return value.trim();
    }
}
