package org.yilena.luna.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

/**
 * Capability types in MCP catalog rows.
 */
@Getter
@AllArgsConstructor
public enum CapabilityTypeEnum {
    PROMPT("PROMPT", "Prompt capability"),
    RESOURCE("RESOURCE", "Resource capability"),
    WORKFLOW("WORKFLOW", "Workflow capability"),
    TOOL("TOOL", "Tool capability"),
    STRATEGY("STRATEGY", "Strategy capability"),
    UNKNOWN("UNKNOWN", "Unknown capability");

    @JsonValue
    private final String code;

    private final String desc;

    public static CapabilityTypeEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return UNKNOWN;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalized))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
