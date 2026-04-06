package org.yilena.luna.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

/**
 * Normalized tool execution status.
 */
@Getter
@AllArgsConstructor
public enum ToolStatusEnum {
    SUCCESS("SUCCESS", "Tool call succeeded"),
    PENDING("PENDING", "Tool call is still pending"),
    FAILED("FAILED", "Tool call failed"),
    UNKNOWN("UNKNOWN", "Tool call status unknown");

    @JsonValue
    private final String code;

    private final String desc;

    public static ToolStatusEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return UNKNOWN;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalized))
                .findFirst()
                .orElse(UNKNOWN);
    }

    public static ToolStatusEnum fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "OK" -> SUCCESS;
            case "RUNNING" -> PENDING;
            case "ERROR" -> FAILED;
            default -> fromCode(normalized);
        };
    }

    public static String[] codes() {
        return Arrays.stream(values())
                .map(ToolStatusEnum::getCode)
                .toArray(String[]::new);
    }
}
