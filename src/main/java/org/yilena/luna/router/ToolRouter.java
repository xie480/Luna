package org.yilena.luna.router;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.enums.RunMode;
import org.yilena.luna.enums.Sensitivity;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRouter {

    private final CapabilityPolicyRouterService capabilityPolicyRouterService;

    public List<Resource> findCandidates(String query) {
        return findCandidates(query, null, null);
    }

    public List<Resource> findCandidates(String query, TaskRuntimeState taskState, RelationalRuntimeState relationalState) {
        log.info("search candidates by query [{}]", query);
        List<Resource> capabilityCandidates = fromCapabilityRegistry(query, taskState, relationalState);
        return capabilityCandidates.size() > 10 ? capabilityCandidates.subList(0, 10) : capabilityCandidates;
    }

    private List<Resource> fromCapabilityRegistry(String query, TaskRuntimeState taskState, RelationalRuntimeState relationalState) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<Map<String, Object>> rows = capabilityPolicyRouterService.routeForExecution(
                    "",
                    query,
                    taskState,
                    relationalState,
                    20
            );
            return rows.stream().map(this::toResource).toList();
        } catch (Exception ignore) {
            return Collections.emptyList();
        }
    }

    private Resource toResource(Map<String, Object> row) {
        Map<String, Object> metadata = mapVal(row.get("metadata_json"));
        String capabilityType = stringVal(row.get("capability_type")).toUpperCase(Locale.ROOT);
        String capabilityName = stringVal(row.get("capability_name"));
        String invocationName = resolveInvocationName(capabilityType, capabilityName, metadata);
        String resourceUri = resolveResourceUri(capabilityName, metadata);
        return Resource.builder()
                .id(stringVal(row.get("capability_id")))
                .name(invocationName)
                .serverCode(stringVal(row.get("server_code")))
                .description(stringVal(row.get("description")))
                .inputSchema(toJsonText(row.get("input_schema")))
                .outputSchema(toJsonText(row.get("output_schema")))
                .resourceUri(resourceUri)
                .requiresApproval(boolVal(row.get("requires_approval")))
                .sensitivity(parseSensitivity(stringVal(row.get("sensitivity"))))
                .version(stringVal(row.get("version")))
                .executionMode(resolveExecutionMode(metadata))
                .type(parseType(capabilityType))
                .runMode(RunMode.SYNC)
                .build();
    }

    private ResourceType parseType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "WORKFLOW" -> ResourceType.WORKFLOW;
            case "PROMPT" -> ResourceType.PROMPT;
            case "RESOURCE" -> ResourceType.RESOURCE;
            case "STRATEGY" -> ResourceType.STRATEGY;
            default -> ResourceType.TOOL;
        };
    }

    private Sensitivity parseSensitivity(String value) {
        if (value == null || value.isBlank()) {
            return Sensitivity.LOW;
        }
        try {
            return Sensitivity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignore) {
            return Sensitivity.LOW;
        }
    }

    private String stringVal(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapVal(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String resolveInvocationName(String capabilityType, String capabilityName, Map<String, Object> metadata) {
        String fromMeta = stringVal(metadata.get("invocation_name"));
        if (!fromMeta.isBlank()) {
            return fromMeta;
        }
        String specificKey = switch (capabilityType) {
            case "PROMPT" -> stringVal(metadata.get("prompt_name"));
            case "RESOURCE" -> stringVal(metadata.get("resource_uri"));
            case "WORKFLOW" -> stringVal(metadata.get("workflow_name"));
            default -> stringVal(metadata.get("tool_name"));
        };
        if (!specificKey.isBlank()) {
            return specificKey;
        }
        int idx = capabilityName.indexOf(':');
        if (idx > -1 && idx + 1 < capabilityName.length()) {
            return capabilityName.substring(idx + 1);
        }
        return capabilityName;
    }

    private String resolveResourceUri(String capabilityName, Map<String, Object> metadata) {
        String fromMeta = stringVal(metadata.get("resource_uri"));
        if (!fromMeta.isBlank()) {
            return fromMeta;
        }
        if (capabilityName.contains("resource://")) {
            int idx = capabilityName.indexOf("resource://");
            return capabilityName.substring(idx);
        }
        return null;
    }

    private Boolean boolVal(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
    }

    private String toJsonText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String resolveExecutionMode(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "MCP";
        }
        String mode = stringVal(metadata.get("execution_mode")).trim().toUpperCase(Locale.ROOT);
        if ("LEGACY".equals(mode) || "MCP".equals(mode)) {
            return mode;
        }
        return "MCP";
    }
}
