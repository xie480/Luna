package org.yilena.luna.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.yilena.luna.constants.McpFieldConstants;
import org.yilena.luna.enums.CapabilityTypeEnum;
import org.yilena.luna.enums.Sensitivity;
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
        CapabilityTypeEnum capabilityType = CapabilityTypeEnum.fromCode(safe(row.get(McpFieldConstants.CAPABILITY_TYPE)));
        String capabilityName = safe(row.get(McpFieldConstants.CAPABILITY_NAME)).toLowerCase(Locale.ROOT);
        String description = safe(row.get(McpFieldConstants.DESCRIPTION)).toLowerCase(Locale.ROOT);
        String schemaWorkflowText = buildSchemaWorkflowText(row);
        String text = capabilityName + " " + description + " " + schemaWorkflowText;

        double score = 1.0;
        score += overlapScore(text, terms);
        score += schemaWorkflowScore(schemaWorkflowText, terms, capabilityType);
        score -= riskPenalty(row);

        if ((taskState == TaskRuntimeState.EXECUTING || taskState == TaskRuntimeState.WAITING_TOOL)
                && capabilityType == CapabilityTypeEnum.TOOL) {
            score += 0.35;
        }
        if ((taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING)
                && capabilityType == CapabilityTypeEnum.WORKFLOW) {
            score += 0.25;
        }
        if (capabilityType == CapabilityTypeEnum.PROMPT || capabilityType == CapabilityTypeEnum.RESOURCE) {
            score += 0.08;
        }
        if ((taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING)
                && (capabilityType == CapabilityTypeEnum.WORKFLOW || capabilityType == CapabilityTypeEnum.STRATEGY)) {
            score += 0.20;
        }
        if ((taskState == TaskRuntimeState.EXECUTING || taskState == TaskRuntimeState.CONTEXT_BUILDING)
                && capabilityType == CapabilityTypeEnum.RESOURCE
                && (text.contains("schema") || text.contains(McpFieldConstants.RESOURCE_URI))) {
            score += 0.10;
        }
        return score;
    }

    private double schemaWorkflowScore(String schemaWorkflowText, String terms, CapabilityTypeEnum capabilityType) {
        if (schemaWorkflowText.isBlank() || terms.isBlank()) {
            return 0.0;
        }
        double score = Math.min(0.65, overlapScore(schemaWorkflowText, terms) * 1.4);
        if (capabilityType == CapabilityTypeEnum.WORKFLOW && containsAny(schemaWorkflowText, "workflow", "phase", "node", "step")) {
            score += 0.18;
        }
        if (capabilityType == CapabilityTypeEnum.TOOL && containsAny(schemaWorkflowText, McpFieldConstants.INPUT_SCHEMA, "required", "parameter", "args")) {
            score += 0.10;
        }
        if (capabilityType == CapabilityTypeEnum.RESOURCE && containsAny(schemaWorkflowText, McpFieldConstants.RESOURCE_URI, McpFieldConstants.DOMAIN, "mime")) {
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
        Sensitivity sensitivity = parseSensitivity(row.get(McpFieldConstants.SENSITIVITY));
        boolean requiresApproval = boolVal(row.get(McpFieldConstants.REQUIRES_APPROVAL));
        double penalty = switch (sensitivity) {
            case HIGH -> 0.45;
            case MEDIUM -> 0.25;
            default -> 0.05;
        };
        if (requiresApproval) {
            penalty += 0.20;
        }
        return penalty;
    }

    private Sensitivity parseSensitivity(Object raw) {
        String normalized = safe(raw).toUpperCase(Locale.ROOT);
        for (Sensitivity one : Sensitivity.values()) {
            if (one.getValue().equalsIgnoreCase(normalized)) {
                return one;
            }
        }
        return Sensitivity.LOW;
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
        sb.append(safe(row.get(McpFieldConstants.INPUT_SCHEMA))).append(' ');
        sb.append(safe(row.get(McpFieldConstants.OUTPUT_SCHEMA))).append(' ');
        sb.append(safe(row.get(McpFieldConstants.TITLE))).append(' ');
        Map<String, Object> metadata = parseMetadata(row.get(McpFieldConstants.METADATA_JSON));
        if (!metadata.isEmpty()) {
            sb.append(safe(metadata.get(McpFieldConstants.WORKFLOW_NAME))).append(' ');
            sb.append(safe(metadata.get(McpFieldConstants.PROCEDURE_TYPE))).append(' ');
            sb.append(safe(metadata.get(McpFieldConstants.DOMAIN))).append(' ');
            sb.append(safe(metadata.get(McpFieldConstants.RESOURCE_URI))).append(' ');
            sb.append(safe(metadata.get(McpFieldConstants.INVOCATION_NAME))).append(' ');
            sb.append(safe(metadata.get(McpFieldConstants.TOOL_NAME))).append(' ');
            sb.append(safe(metadata.get(McpFieldConstants.PROMPT_NAME))).append(' ');
            sb.append(safe(metadata.get(McpFieldConstants.REQUIRED_CAPABILITIES))).append(' ');
            sb.append(safe(metadata.get(McpFieldConstants.TOOL_SLOTS))).append(' ');
            sb.append(safe(metadata.get(McpFieldConstants.PATTERN_STEPS_JSON))).append(' ');
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
