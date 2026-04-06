package org.yilena.luna.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

/**
 * Update modes accepted by schedule management tool.
 */
@Getter
@AllArgsConstructor
public enum ScheduleUpdateModeEnum {
    PUT("PUT", "Full update"),
    PATCH("PATCH", "Partial update");

    @JsonValue
    private final String code;

    private final String desc;

    public static ScheduleUpdateModeEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("schedule update mode is required");
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported schedule update mode: " + code));
    }
}
