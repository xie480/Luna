package org.yilena.luna.context;

import org.springframework.stereotype.Component;
import org.yilena.luna.context.model.ToolSemanticResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ToolSemanticResultValidator {

    private static final List<String> ALLOWED_STATUS = List.of("SUCCESS", "PENDING", "FAILED", "UNKNOWN");

    public ValidationResult validate(ToolSemanticResult result) {
        List<String> issues = new ArrayList<>();
        if (result == null) {
            issues.add("semantic_result_missing");
            return new ValidationResult(false, issues, null);
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

        if (!issues.isEmpty()) {
            return new ValidationResult(false, issues, normalizeResult(result, status));
        }
        return new ValidationResult(true, List.of(), normalizeResult(result, status));
    }

    private ToolSemanticResult normalizeResult(ToolSemanticResult result, String normalizedStatus) {
        if (result == null) {
            return null;
        }
        double confidence = Math.max(0.0, Math.min(result.getConfidence(), 1.0));
        return ToolSemanticResult.builder()
                .toolStatus(normalizedStatus)
                .keyFacts(result.getKeyFacts() == null ? List.of("raw_result_available") : result.getKeyFacts())
                .businessImpact(result.getBusinessImpact() == null ? "" : result.getBusinessImpact())
                .unresolvedIssues(result.getUnresolvedIssues() == null ? List.of() : result.getUnresolvedIssues())
                .nextStepHint(result.getNextStepHint() == null ? "" : result.getNextStepHint())
                .confidence(confidence)
                .semanticPayload(result.getSemanticPayload() == null ? Map.of() : result.getSemanticPayload())
                .build();
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

