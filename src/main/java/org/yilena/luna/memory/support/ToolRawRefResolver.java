package org.yilena.luna.memory.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ToolRawRefResolver {

    private ToolRawRefResolver() {
    }

    public static String resolveRawJson(String rawRef,
                                        List<Map<String, Object>> toolRows,
                                        ObjectMapper objectMapper) {
        if (toolRows == null || toolRows.isEmpty()) {
            return "";
        }
        Ref ref = parse(rawRef);
        for (Map<String, Object> row : toolRows) {
            if (row == null || !matches(ref, row)) {
                continue;
            }
            return normalizeJsonString(row.get("normalized_output"), objectMapper);
        }
        return "";
    }

    private static boolean matches(Ref ref, Map<String, Object> row) {
        if (ref.type == RefType.LATEST) {
            return true;
        }
        if (ref.type == RefType.TRACE_ID) {
            return ref.traceId.equals(str(row.get("trace_id")));
        }
        if (ref.type == RefType.NAME_STATUS) {
            String name = str(row.get("tool_name")).toLowerCase(Locale.ROOT);
            String status = normalizeStatus(row.get("call_status"));
            return ref.toolName.equals(name) && ref.callStatus.equals(status);
        }
        return false;
    }

    private static Ref parse(String rawRef) {
        String normalized = rawRef == null ? "" : rawRef.trim();
        if (normalized.isBlank() || "tool_execution_trace:latest".equalsIgnoreCase(normalized)) {
            return new Ref(RefType.LATEST, "", "", "");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("tool_execution_trace:id=")) {
            String traceId = normalized.substring("tool_execution_trace:id=".length()).trim();
            return new Ref(RefType.TRACE_ID, traceId, "", "");
        }
        if (lower.startsWith("tool_execution_trace:")) {
            String suffix = normalized.substring("tool_execution_trace:".length()).trim();
            int separator = suffix.lastIndexOf(':');
            if (separator > 0) {
                String toolName = suffix.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                String callStatus = suffix.substring(separator + 1).trim().toUpperCase(Locale.ROOT);
                if (!toolName.isBlank()) {
                    return new Ref(RefType.NAME_STATUS, "", toolName, callStatus);
                }
            }
        }
        return new Ref(RefType.LATEST, "", "", "");
    }

    private static String normalizeStatus(Object rawStatus) {
        String status = str(rawStatus).trim().toUpperCase(Locale.ROOT);
        return status.isBlank() ? "UNKNOWN" : status;
    }

    private static String normalizeJsonString(Object raw, ObjectMapper objectMapper) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (Exception ignore) {
            return String.valueOf(raw);
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private enum RefType {
        LATEST,
        TRACE_ID,
        NAME_STATUS
    }

    private record Ref(RefType type, String traceId, String toolName, String callStatus) {
    }
}
