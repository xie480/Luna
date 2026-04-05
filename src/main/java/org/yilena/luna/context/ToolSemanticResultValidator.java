package org.yilena.luna.context;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yilena.luna.common.utils.JsonSchemaValidator;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.ArrayList;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ToolSemanticResultValidator {

    private static final List<String> ALLOWED_STATUS = List.of("SUCCESS", "PENDING", "FAILED", "UNKNOWN");
    private static final int DEFAULT_SEMANTIC_TOKEN_BUDGET = 1200;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final ToolSemanticSchemaProvider schemaProvider;

    public ToolSemanticResultValidator() {
        this(new ToolSemanticSchemaProvider());
    }

    @Autowired
    public ToolSemanticResultValidator(ToolSemanticSchemaProvider schemaProvider) {
        this.schemaProvider = schemaProvider;
    }

    public ValidationResult validate(ToolSemanticResult result) {
        return validate(result, null);
    }

    public ValidationResult validate(ToolSemanticResult result, StructuredContextPackage contextPackage) {
        List<String> issues = new ArrayList<>();
        if (result == null) {
            issues.add("semantic_result_missing");
            return new ValidationResult(false, issues, null);
        }
        if (!schemaValid(result)) {
            issues.add("schema_invalid");
            return new ValidationResult(false, issues, minimalFallback(result, issues));
        }

        String status = normalize(result.getToolStatus());
        if (!ALLOWED_STATUS.contains(status)) {
            issues.add("invalid_status");
        }
        if (result.getKeyFacts() == null || result.getKeyFacts().isEmpty()) {
            issues.add("missing_key_facts");
        }
        if (result.getBusinessImpact() == null || result.getBusinessImpact().isBlank()) {
            issues.add("missing_business_impact");
        }
        if (result.getNextStepHint() == null || result.getNextStepHint().isBlank()) {
            issues.add("missing_next_step_hint");
        }
        if (result.getConfidence() < 0.0 || result.getConfidence() > 1.0) {
            issues.add("confidence_out_of_range");
        }
        if (result.getSemanticPayload() == null || result.getSemanticPayload().isEmpty()) {
            issues.add("missing_semantic_payload");
        }
        issues.addAll(validatePayloadConsistency(result, status));
        issues.addAll(validateBudget(result, contextPackage));
        issues.addAll(validateStateConflicts(result, status, contextPackage));

        if (!issues.isEmpty()) {
            return new ValidationResult(false, issues, normalizeResult(result, status, issues));
        }
        return new ValidationResult(true, List.of(), normalizeResult(result, status, List.of()));
    }

    private ToolSemanticResult normalizeResult(ToolSemanticResult result, String normalizedStatus, List<String> issues) {
        if (result == null) {
            return null;
        }
        double confidence = Math.max(0.0, Math.min(result.getConfidence(), 1.0));
        Map<String, Object> payload = normalizePayload(result.getSemanticPayload(), normalizedStatus, issues);
        return ToolSemanticResult.builder()
                .toolName(result.getToolName() == null ? "" : result.getToolName())
                .toolDescription(result.getToolDescription() == null ? "" : result.getToolDescription())
                .rawResultDigest(result.getRawResultDigest() == null ? "" : result.getRawResultDigest())
                .toolStatus(normalizedStatus)
                .keyFacts(result.getKeyFacts() == null ? List.of("raw_result_available") : result.getKeyFacts())
                .businessImpact(result.getBusinessImpact() == null ? "" : result.getBusinessImpact())
                .unresolvedIssues(result.getUnresolvedIssues() == null ? List.of() : result.getUnresolvedIssues())
                .nextStepHint(result.getNextStepHint() == null ? "" : result.getNextStepHint())
                .confidence(confidence)
                .semanticPayload(payload)
                .build();
    }

    private ToolSemanticResult minimalFallback(ToolSemanticResult result, List<String> issues) {
        String status = normalize(result == null ? null : result.getToolStatus());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("schema_invalid", true);
        payload.put("validationIssues", issues == null ? List.of("schema_invalid") : issues);
        payload.put("rawResultDigest", result == null ? "" : stringValue(result.getRawResultDigest()));
        return ToolSemanticResult.builder()
                .toolName(result == null ? "" : stringValue(result.getToolName()))
                .toolDescription(result == null ? "" : stringValue(result.getToolDescription()))
                .rawResultDigest(result == null ? "" : stringValue(result.getRawResultDigest()))
                .toolStatus(status)
                .keyFacts(List.of("schema_invalid"))
                .businessImpact("Tool semantic result schema invalid, downgraded to minimal semantic channel.")
                .unresolvedIssues(List.of("schema_invalid"))
                .nextStepHint("fallback_to_raw_channel")
                .confidence(0.0)
                .semanticPayload(payload)
                .build();
    }

    private boolean schemaValid(ToolSemanticResult result) {
        if (result == null) {
            return false;
        }
        try {
            String json = OBJECT_MAPPER.writeValueAsString(result);
            return JsonSchemaValidator.validate(schemaProvider.toolSemanticSchema(), json);
        } catch (Exception ignore) {
            return false;
        }
    }

    private List<String> validatePayloadConsistency(ToolSemanticResult result, String status) {
        List<String> issues = new ArrayList<>();
        Map<String, Object> payload = result.getSemanticPayload() == null ? Map.of() : result.getSemanticPayload();
        String payloadStatus = normalize(stringValue(payload.get("status")));
        if (!payloadStatus.equals("UNKNOWN") && !payloadStatus.equals(status)) {
            issues.add("payload_status_mismatch");
        }
        if ("SUCCESS".equals(status) && result.getUnresolvedIssues() != null && !result.getUnresolvedIssues().isEmpty()) {
            issues.add("success_with_unresolved_issues");
        }
        if ("FAILED".equals(status) && (result.getUnresolvedIssues() == null || result.getUnresolvedIssues().isEmpty())) {
            issues.add("failed_without_unresolved_issues");
        }
        return issues;
    }

    private List<String> validateBudget(ToolSemanticResult result, StructuredContextPackage contextPackage) {
        List<String> issues = new ArrayList<>();
        int semanticTokens = estimateTokens(result);
        int tokenBudget = resolveTokenBudget(contextPackage);
        if (semanticTokens > tokenBudget) {
            issues.add("semantic_payload_budget_exceeded");
        }
        Double stateBudget = resolveStateBudget(contextPackage);
        Double toolCost = resolveToolCost(result == null ? Map.of() : result.getSemanticPayload());
        if (stateBudget != null && toolCost != null && toolCost > stateBudget) {
            issues.add("budget_conflict_with_current_state");
        }
        return issues;
    }

    private List<String> validateStateConflicts(ToolSemanticResult result,
                                                String status,
                                                StructuredContextPackage contextPackage) {
        List<String> issues = new ArrayList<>();
        if (contextPackage == null) {
            return issues;
        }
        TaskRuntimeState runtimeState = contextPackage.getTaskState();
        if (runtimeState == TaskRuntimeState.WAITING_APPROVAL && "SUCCESS".equals(status)) {
            issues.add("status_conflict_waiting_approval");
        }
        if (runtimeState == TaskRuntimeState.WAITING_TOOL && "UNKNOWN".equals(status)) {
            issues.add("status_conflict_waiting_tool");
        }
        String nextStep = result.getNextStepHint() == null ? "" : result.getNextStepHint().toLowerCase(Locale.ROOT);
        if ("PENDING".equals(status) && containsAny(nextStep, "continue reasoning", "继续推理", "execute")) {
            issues.add("pending_next_step_conflict");
        }
        String lastToolName = contextPackage.getToolState() == null ? "" : stringValue(contextPackage.getToolState().getLastToolName());
        String payloadToolName = stringValue(result.getSemanticPayload() == null ? null : result.getSemanticPayload().get("tool"));
        if (!lastToolName.isBlank() && !payloadToolName.isBlank() && !lastToolName.equalsIgnoreCase(payloadToolName)) {
            issues.add("tool_name_conflict_with_state");
        }
        return issues;
    }

    private Map<String, Object> normalizePayload(Map<String, Object> payload, String normalizedStatus, List<String> issues) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (payload != null) {
            normalized.putAll(payload);
        }
        normalized.put("status", normalizedStatus);
        if (issues != null && issues.contains("semantic_payload_budget_exceeded")) {
            normalized = trimPayloadForBudget(normalized);
        }
        return normalized;
    }

    private Map<String, Object> trimPayloadForBudget(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of("status", "UNKNOWN", "budgetTrimmed", true);
        }
        Map<String, Object> trimmed = new LinkedHashMap<>();
        putIfPresent(trimmed, "status", payload);
        putIfPresent(trimmed, "tool", payload);
        putIfPresent(trimmed, "workflow", payload);
        putIfPresent(trimmed, "taskId", payload);
        putIfPresent(trimmed, "keyFacts", payload);
        putIfPresent(trimmed, "businessImpact", payload);
        putIfPresent(trimmed, "unresolvedIssues", payload);
        putIfPresent(trimmed, "nextStepHint", payload);
        putIfPresent(trimmed, "confidence", payload);
        trimmed.put("budgetTrimmed", true);
        return trimmed;
    }

    private void putIfPresent(Map<String, Object> target, String key, Map<String, Object> source) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private int resolveTokenBudget(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTokenBudgetPlan() == null || contextPackage.getTokenBudgetPlan().isEmpty()) {
            return DEFAULT_SEMANTIC_TOKEN_BUDGET;
        }
        Integer budget = contextPackage.getTokenBudgetPlan().get("task_procedures");
        if (budget == null || budget <= 0) {
            return DEFAULT_SEMANTIC_TOKEN_BUDGET;
        }
        return budget;
    }

    private int estimateTokens(ToolSemanticResult result) {
        if (result == null) {
            return 0;
        }
        int chars = 0;
        chars += stringValue(result.getBusinessImpact()).length();
        chars += stringValue(result.getNextStepHint()).length();
        chars += result.getKeyFacts() == null ? 0 : result.getKeyFacts().stream().mapToInt(item -> stringValue(item).length()).sum();
        chars += result.getUnresolvedIssues() == null ? 0 : result.getUnresolvedIssues().stream().mapToInt(item -> stringValue(item).length()).sum();
        chars += stringValue(result.getSemanticPayload()).length();
        return Math.max(1, chars / 4);
    }

    private Double resolveStateBudget(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskStateEntity() == null || contextPackage.getTaskStateEntity().getConfirmedSlots() == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : contextPackage.getTaskStateEntity().getConfirmedSlots().entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (!key.contains("budget")) {
                continue;
            }
            Double parsed = parseDouble(entry.getValue());
            if (parsed != null && parsed >= 0) {
                return parsed;
            }
        }
        return null;
    }

    private Double resolveToolCost(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (!(key.contains("cost") || key.contains("expense") || key.contains("budget_used"))) {
                continue;
            }
            Double parsed = parseDouble(entry.getValue());
            if (parsed != null && parsed >= 0) {
                return parsed;
            }
        }
        return null;
    }

    private Double parseDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).replaceAll("[^0-9.\\-]", "");
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(normalized);
        } catch (Exception ignore) {
            return null;
        }
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalize(String status) {
        if (status == null || status.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = status.toUpperCase(Locale.ROOT).trim();
        if ("OK".equals(normalized)) {
            return "SUCCESS";
        }
        if ("RUNNING".equals(normalized)) {
            return "PENDING";
        }
        if ("ERROR".equals(normalized)) {
            return "FAILED";
        }
        return normalized;
    }

    public record ValidationResult(boolean valid, List<String> issues, ToolSemanticResult normalized) {
    }
}
