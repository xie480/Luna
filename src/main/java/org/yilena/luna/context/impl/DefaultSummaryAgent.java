package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.SummaryAgent;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
/**
 * 轮次摘要代理默认实现，负责将当前对话轮次压缩为叙事摘要和状态快照，
 * 为后续上下文裁剪、恢复和状态延续提供稳定输入。
 */
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
            fullShortTermMemory=%s
            latestToolName=%s
            latestToolStatus=%s
            pendingQuestions=%s
            retrievalIntent=%s
            retrievalActiveQueries=%s
            retrievalPlan=%s
            retrievalSelectedEvidenceRefs=%s
            retrievalRerankSummary=%s
            activeEvidenceCount=%s
            activeEvidenceDigest=%s
            mcpHintCount=%s
            mcpHintDigest=%s
            latestToolSemantic=%s
            """;

    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private PromptRegistryService promptRegistryService;

    @Override
    /**
     * 总结当前轮次的用户输入、助手回复与上下文证据，生成可持久化的摘要结果。
     */
    public SummaryResult summarize(String userInput,
                                   String assistantReply,
                                   StructuredContextPackage contextPackage,
                                   List<EvidenceBlock> activeEvidenceBlocks,
                                   List<String> activeMcpResourceHints,
                                   ToolSemanticResult latestToolSemanticResult) {
        /**
         * 优先使用模型生成结构化摘要，
         * 以获得更完整的叙事归纳和状态快照表达。
         */
        SummaryResult llmSummary = tryModelSummary(
                userInput,
                assistantReply,
                contextPackage,
                activeEvidenceBlocks,
                activeMcpResourceHints,
                latestToolSemanticResult
        );
        if (llmSummary != null) {
            return llmSummary;
        }
        /**
         * 模型摘要失败时回退到本地拼接逻辑，
         * 保证上下文链路始终能拿到可用的叙事摘要和状态快照。
         */
        String narrative = buildNarrative(
                userInput,
                assistantReply,
                contextPackage,
                activeEvidenceBlocks,
                activeMcpResourceHints,
                latestToolSemanticResult
        );
        Map<String, Object> snapshot = buildStateSnapshot(contextPackage, latestToolSemanticResult);
        return SummaryResult.builder()
                .narrativeSummary(narrative)
                .stateSnapshot(snapshot)
                .build();
    }

    private SummaryResult tryModelSummary(String userInput,
                                          String assistantReply,
                                          StructuredContextPackage contextPackage,
                                          List<EvidenceBlock> activeEvidenceBlocks,
                                          List<String> activeMcpResourceHints,
                                          ToolSemanticResult latestToolSemanticResult) {
        try {
            /**
             * 将短期记忆、检索结果、证据摘要和工具语义压缩进提示词，
             * 引导模型输出统一结构的轮次摘要。
             */
            String promptTemplate = promptRegistryService == null
                    ? SUMMARY_PROMPT
                    : promptRegistryService.resolvePromptValue("agent-local.summary.default_v1", SUMMARY_PROMPT);
            String prompt = promptTemplate.formatted(
                    safe(userInput),
                    safe(assistantReply),
                    contextPackage == null || contextPackage.getTaskState() == null ? "UNKNOWN" : contextPackage.getTaskState().name(),
                    contextPackage == null || contextPackage.getRelationalState() == null ? "UNKNOWN" : contextPackage.getRelationalState().name(),
                    contextPackage == null || contextPackage.getRecentMessages() == null ? 0 : contextPackage.getRecentMessages().size(),
                    buildShortTermMemoryDigest(contextPackage),
                    contextPackage == null || contextPackage.getToolState() == null ? "" : safe(contextPackage.getToolState().getLastToolName()),
                    contextPackage == null || contextPackage.getToolState() == null ? "" : safe(contextPackage.getToolState().getLastToolStatus()),
                    contextPackage == null || contextPackage.getTaskStateEntity() == null ? "" : safe(contextPackage.getTaskStateEntity().getPendingQuestions()),
                    contextPackage == null || contextPackage.getRetrievalState() == null ? "" : safe(contextPackage.getRetrievalState().getReconstructedIntent()),
                    contextPackage == null || contextPackage.getRetrievalState() == null ? "" : safe(contextPackage.getRetrievalState().getActiveQueries()),
                    contextPackage == null || contextPackage.getRetrievalState() == null ? "" : safe(contextPackage.getRetrievalState().getRetrievalPlan()),
                    contextPackage == null || contextPackage.getRetrievalState() == null ? "" : safe(contextPackage.getRetrievalState().getSelectedEvidenceRefs()),
                    contextPackage == null || contextPackage.getRetrievalState() == null ? "" : safe(contextPackage.getRetrievalState().getRerankSummary()),
                    activeEvidenceBlocks == null ? 0 : activeEvidenceBlocks.size(),
                    buildEvidenceDigest(activeEvidenceBlocks),
                    activeMcpResourceHints == null ? 0 : activeMcpResourceHints.size(),
                    buildListDigest(activeMcpResourceHints, 8),
                    latestToolSemanticResult == null ? "{}" : safe(latestToolSemanticResult.getSemanticPayload())
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
            /**
             * 对模型快照结果做标准化补齐，
             * 确保缺失字段能够回落到本地状态推断值。
             */
            JsonNode node = objectMapper.readTree(stripFence(content));
            String narrative = node.path("narrativeSummary").asText("");
            if (narrative.isBlank()) {
                return null;
            }
            Map<String, Object> snapshot = node.path("stateSnapshot").isObject()
                    ? objectMapper.convertValue(node.path("stateSnapshot"), Map.class)
                    : Map.of();
            snapshot = normalizeStateSnapshot(snapshot, contextPackage, latestToolSemanticResult);
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
            if (contextPackage.getRetrievalState() != null) {
                sb.append("Retrieval intent=").append(safe(contextPackage.getRetrievalState().getReconstructedIntent()))
                        .append(", active queries=").append(safe(contextPackage.getRetrievalState().getActiveQueries()))
                        .append(", selected evidence refs=").append(safe(contextPackage.getRetrievalState().getSelectedEvidenceRefs()))
                        .append(". ");
            }
        }
        sb.append("Assistant response delivered: ").append(safe(assistantReply));
        return sb.toString().trim();
    }

    private String buildNarrative(String userInput,
                                  String assistantReply,
                                  StructuredContextPackage contextPackage,
                                  List<EvidenceBlock> activeEvidenceBlocks,
                                  List<String> activeMcpResourceHints,
                                  ToolSemanticResult latestToolSemanticResult) {
        String base = buildNarrative(userInput, assistantReply, contextPackage);
        StringBuilder sb = new StringBuilder(base);
        if (activeEvidenceBlocks != null && !activeEvidenceBlocks.isEmpty()) {
            sb.append(" Active evidences=").append(activeEvidenceBlocks.size())
                    .append(", digest=").append(buildEvidenceDigest(activeEvidenceBlocks)).append(". ");
        }
        if (activeMcpResourceHints != null && !activeMcpResourceHints.isEmpty()) {
            sb.append(" Active MCP hints=").append(buildListDigest(activeMcpResourceHints, 8)).append(". ");
        }
        if (latestToolSemanticResult != null) {
            sb.append(" Latest tool semantic status=").append(safe(latestToolSemanticResult.getToolStatus()))
                    .append(", nextStep=").append(safe(latestToolSemanticResult.getNextStepHint())).append(". ");
        }
        return sb.toString().trim();
    }

    private String buildShortTermMemoryDigest(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRecentMessages() == null || contextPackage.getRecentMessages().isEmpty()) {
            return "";
        }
        List<Map<String, Object>> recentMessages = contextPackage.getRecentMessages();
        StringBuilder digest = new StringBuilder(Math.max(4096, recentMessages.size() * 120));
        int index = 0;
        for (Map<String, Object> row : recentMessages) {
            String role = safe(row.get("role"));
            String content = safe(row.get("content_text")).replaceAll("\\s+", " ").trim();
            if (role.isBlank() && content.isBlank()) {
                continue;
            }
            digest.append(index++)
                    .append(":")
                    .append("[")
                    .append(role)
                    .append("] ")
                    .append(content)
                    .append("\n");
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

    private String buildEvidenceDigest(List<EvidenceBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        StringBuilder digest = new StringBuilder(1024);
        int limit = Math.min(6, blocks.size());
        for (int i = 0; i < limit; i++) {
            EvidenceBlock block = blocks.get(i);
            if (block == null) {
                continue;
            }
            digest.append("[")
                    .append(safe(block.getBlockId()))
                    .append("]")
                    .append(compactContent(safe(block.getTitle()) + " " + safe(block.getContent()), 120))
                    .append(" | ");
        }
        return digest.toString().trim();
    }

    private String buildListDigest(List<String> values, int maxItems) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream().limit(maxItems).map(this::safe).collect(Collectors.joining(" | "));
    }

    private Map<String, Object> normalizeStateSnapshot(Map<String, Object> rawSnapshot,
                                                       StructuredContextPackage contextPackage,
                                                       ToolSemanticResult latestToolSemanticResult) {
        Map<String, Object> fallback = buildStateSnapshot(contextPackage, latestToolSemanticResult);
        Map<String, Object> raw = rawSnapshot == null ? Map.of() : rawSnapshot;
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("currentStage", textOrDefault(raw.get("currentStage"), fallback.get("currentStage")));
        normalized.put("confirmedSlots", mapOrDefault(raw.get("confirmedSlots"), mapOrDefault(raw.get("confirmed_params"), fallback.get("confirmedSlots"))));
        normalized.put("finishedSteps", listOrDefault(raw.get("finishedSteps"), fallback.get("finishedSteps")));
        normalized.put("pendingIssues", listOrDefault(raw.get("pendingIssues"), listOrDefault(raw.get("pendingQuestions"), fallback.get("pendingIssues"))));
        normalized.put("latestToolConclusion", textOrDefault(raw.get("latestToolConclusion"), fallback.get("latestToolConclusion")));
        normalized.put("currentConstraints", listOrDefault(raw.get("currentConstraints"), listOrDefault(raw.get("constraints"), fallback.get("currentConstraints"))));
        normalized.put("nextStep", textOrDefault(raw.get("nextStep"), fallback.get("nextStep")));
        return normalized;
    }

    private Map<String, Object> buildStateSnapshot(StructuredContextPackage contextPackage,
                                                   ToolSemanticResult latestToolSemanticResult) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (contextPackage == null) {
            snapshot.put("currentStage", "UNKNOWN");
            snapshot.put("confirmedSlots", Map.of());
            snapshot.put("finishedSteps", List.of());
            snapshot.put("pendingIssues", List.of("state_context_missing"));
            snapshot.put("latestToolConclusion", "");
            snapshot.put("currentConstraints", List.of());
            snapshot.put("nextStep", "continue");
            return snapshot;
        }
        /**
         * 汇总待处理问题、当前约束和最近工具结论，
         * 生成后续轮次可继续承接的状态快照。
         */
        List<String> pendingIssues = new java.util.ArrayList<>();
        if (contextPackage.getTaskStateEntity() != null) {
            pendingIssues.addAll(contextPackage.getTaskStateEntity().getPendingQuestions() == null
                    ? List.of()
                    : contextPackage.getTaskStateEntity().getPendingQuestions());
            if (contextPackage.getTaskStateEntity().getFailedSteps() != null) {
                pendingIssues.addAll(contextPackage.getTaskStateEntity().getFailedSteps().stream()
                        .map(step -> "failed_step:" + safe(step))
                        .toList());
            }
        }
        List<String> currentConstraints = extractCurrentConstraints(contextPackage);
        String latestToolConclusion = latestToolSemanticResult == null ? "" : safe(latestToolSemanticResult.getBusinessImpact());
        if (latestToolConclusion.isBlank() && contextPackage.getToolState() != null) {
            latestToolConclusion = safe(contextPackage.getToolState().getLastToolSemanticSummary());
        }

        snapshot.put("currentStage", contextPackage.getTaskState() == null ? "UNKNOWN" : contextPackage.getTaskState().name());
        snapshot.put("confirmedSlots", contextPackage.getTaskStateEntity() == null || contextPackage.getTaskStateEntity().getConfirmedSlots() == null
                ? Map.of()
                : contextPackage.getTaskStateEntity().getConfirmedSlots());
        snapshot.put("finishedSteps", contextPackage.getTaskStateEntity() == null || contextPackage.getTaskStateEntity().getFinishedSteps() == null
                ? List.of()
                : contextPackage.getTaskStateEntity().getFinishedSteps());
        snapshot.put("pendingIssues", pendingIssues.stream().filter(v -> v != null && !v.isBlank()).distinct().toList());
        snapshot.put("latestToolConclusion", latestToolConclusion);
        snapshot.put("currentConstraints", currentConstraints);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOrDefault(Object candidate, Object fallback) {
        if (candidate instanceof Map<?, ?> map && !map.isEmpty()) {
            return (Map<String, Object>) map;
        }
        if (fallback instanceof Map<?, ?> map && !map.isEmpty()) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> listOrDefault(Object candidate, Object fallback) {
        if (candidate instanceof List<?> list && !list.isEmpty()) {
            return (List<String>) list.stream().map(this::safe).filter(v -> !v.isBlank()).toList();
        }
        if (fallback instanceof List<?> list && !list.isEmpty()) {
            return (List<String>) list.stream().map(this::safe).filter(v -> !v.isBlank()).toList();
        }
        return List.of();
    }

    private String textOrDefault(Object candidate, Object fallback) {
        String value = safe(candidate);
        if (!value.isBlank()) {
            return value;
        }
        return safe(fallback);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractCurrentConstraints(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return List.of();
        }
        Object working = contextPackage.getTaskContext().get("working_memory");
        if (!(working instanceof Map<?, ?> map)) {
            return List.of();
        }
        List<String> constraints = new java.util.ArrayList<>();
        constraints.add(safe(((Map<String, Object>) map).get("constraints_json")));
        constraints.add(safe(((Map<String, Object>) map).get("success_criteria_json")));
        return constraints.stream().filter(item -> item != null && !item.isBlank()).distinct().toList();
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
