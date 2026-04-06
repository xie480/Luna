package org.yilena.luna.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

/**
 * Actions accepted by schedule management tool.
 */
@Getter
@AllArgsConstructor
public enum ScheduleActionEnum {
    INSERT("INSERT", "Insert task"),
    QUERY("QUERY", "Query task"),
    UPDATE("UPDATE", "Update task"),
    DELETE("DELETE", "Delete task");

    @JsonValue
    private final String code;

    private final String desc;

    public static ScheduleActionEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("schedule action is required");
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported schedule action: " + code));
    }
}
