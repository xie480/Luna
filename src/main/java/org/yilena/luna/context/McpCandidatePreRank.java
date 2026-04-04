package org.yilena.luna.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class McpCandidatePreRank {

    private final ObjectMapper objectMapper;

    public McpCandidatePreRank(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> preRank(String mcpQuery,
                                             List<Map<String, Object>> candidates,
                                             InputReconstructionResult reconstructionResult,
                                             TaskRuntimeState taskState,
                                             int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String terms = buildTerms(mcpQuery, reconstructionResult, taskState);
        return candidates.stream()
                .map(this::copy)
                .sorted(Comparator.comparingDouble((Map<String, Object> row) -> score(row, terms, taskState)).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    private double score(Map<String, Object> row, String terms, TaskRuntimeState taskState) {
        String capabilityType = safe(row.get("capability_type")).toUpperCase(Locale.ROOT);
        String capabilityName = safe(row.get("capability_name")).toLowerCase(Locale.ROOT);
        String description = safe(row.get("description")).toLowerCase(Locale.ROOT);
        String schemaWorkflowText = buildSchemaWorkflowText(row);
        String text = capabilityName + " " + description + " " + schemaWorkflowText;

        double score = 1.0;
        score += overlapScore(text, terms);
        score += schemaWorkflowScore(schemaWorkflowText, terms, capabilityType);
        score -= riskPenalty(row);

        if ((taskState == TaskRuntimeState.EXECUTING || taskState == TaskRuntimeState.WAITING_TOOL)
                && "TOOL".equals(capabilityType)) {
            score += 0.35;
        }
        if ((taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING)
                && "WORKFLOW".equals(capabilityType)) {
            score += 0.25;
        }
        if ("PROMPT".equals(capabilityType) || "RESOURCE".equals(capabilityType)) {
            score += 0.08;
        }
        if ((taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING)
                && ("WORKFLOW".equals(capabilityType) || "STRATEGY".equals(capabilityType))) {
            score += 0.20;
        }
        if ((taskState == TaskRuntimeState.EXECUTING || taskState == TaskRuntimeState.CONTEXT_BUILDING)
                && "RESOURCE".equals(capabilityType)
                && (text.contains("schema") || text.contains("resource_uri"))) {
            score += 0.10;
        }
        return score;
    }

    private double schemaWorkflowScore(String schemaWorkflowText, String terms, String capabilityType) {
        if (schemaWorkflowText.isBlank() || terms.isBlank()) {
            return 0.0;
        }
        double score = Math.min(0.65, overlapScore(schemaWorkflowText, terms) * 1.4);
        if ("WORKFLOW".equals(capabilityType) && containsAny(schemaWorkflowText, "workflow", "phase", "node", "step")) {
            score += 0.18;
        }
        if ("TOOL".equals(capabilityType) && containsAny(schemaWorkflowText, "input_schema", "required", "parameter", "args")) {
            score += 0.10;
        }
        if ("RESOURCE".equals(capabilityType) && containsAny(schemaWorkflowText, "resource_uri", "domain", "mime")) {
            score += 0.08;
        }
        return score;
    }

    private double overlapScore(String text, String terms) {
        if (text.isBlank() || terms.isBlank()) {
            return 0.0;
        }
        List<String> parts = new ArrayList<>();
        for (String one : terms.split("[\\s,;|]+")) {
            if (one != null && !one.isBlank() && one.length() >= 2) {
                parts.add(one.toLowerCase(Locale.ROOT));
            }
        }
        if (parts.isEmpty()) {
            return 0.0;
        }
        int hits = 0;
        for (String one : parts) {
            if (text.contains(one)) {
                hits++;
            }
        }
        return Math.min(0.8, hits * 0.08);
    }

    private double riskPenalty(Map<String, Object> row) {
        String sensitivity = safe(row.get("sensitivity")).toUpperCase(Locale.ROOT);
        boolean requiresApproval = boolVal(row.get("requires_approval"));
        double penalty = switch (sensitivity) {
            case "HIGH" -> 0.45;
            case "MEDIUM" -> 0.25;
            default -> 0.05;
        };
        if (requiresApproval) {
            penalty += 0.20;
        }
        return penalty;
    }

    private String buildTerms(String mcpQuery, InputReconstructionResult reconstructionResult, TaskRuntimeState taskState) {
        StringBuilder terms = new StringBuilder();
        if (mcpQuery != null) {
            terms.append(mcpQuery).append(' ');
        }
        if (reconstructionResult != null) {
            terms.append(safe(reconstructionResult.getExplicitTaskGoal())).append(' ');
            terms.append(safe(reconstructionResult.getNormalizedUserIntent())).append(' ');
            terms.append(safe(reconstructionResult.getBlueprintHint())).append(' ');
            terms.append(safe(reconstructionResult.getReformulatedQueryForMcp())).append(' ');
            if (reconstructionResult.getClarifiedEntities() != null) {
                terms.append(reconstructionResult.getClarifiedEntities().values());
            }
            terms.append(' ');
            if (reconstructionResult.getBusinessConstraints() != null) {
                terms.append(reconstructionResult.getBusinessConstraints());
            }
        }
        terms.append(' ').append(taskState == null ? "UNKNOWN" : taskState.name());
        return terms.toString().trim();
    }

    private String buildSchemaWorkflowText(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(safe(row.get("input_schema"))).append(' ');
        sb.append(safe(row.get("output_schema"))).append(' ');
        sb.append(safe(row.get("title"))).append(' ');
        Map<String, Object> metadata = parseMetadata(row.get("metadata_json"));
        if (!metadata.isEmpty()) {
            sb.append(safe(metadata.get("workflow_name"))).append(' ');
            sb.append(safe(metadata.get("procedure_type"))).append(' ');
            sb.append(safe(metadata.get("domain"))).append(' ');
            sb.append(safe(metadata.get("resource_uri"))).append(' ');
            sb.append(safe(metadata.get("invocation_name"))).append(' ');
            sb.append(safe(metadata.get("tool_name"))).append(' ');
            sb.append(safe(metadata.get("prompt_name"))).append(' ');
            sb.append(safe(metadata.get("required_capabilities"))).append(' ');
            sb.append(safe(metadata.get("tool_slots"))).append(' ');
            sb.append(safe(metadata.get("pattern_steps_json"))).append(' ');
        }
        return sb.toString().toLowerCase(Locale.ROOT).trim();
    }

    private Map<String, Object> parseMetadata(Object metadataValue) {
        if (metadataValue == null) {
            return Map.of();
        }
        if (metadataValue instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(entry.getKey() == null ? "" : String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        String raw = safe(metadataValue).trim();
        if (raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception ignore) {
            return Map.of();
        }
    }

    private Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? Map.of() : new LinkedHashMap<>(source);
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean boolVal(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
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
}
