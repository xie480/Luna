package org.yilena.luna.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

@Getter
@AllArgsConstructor
public enum ToolActionEnum {
    INSERT("INSERT", "Insert"),
    QUERY("QUERY", "Query"),
    UPDATE("UPDATE", "Update"),
    DELETE("DELETE", "Delete");

    private final String code;
    private final String desc;

    public static Optional<ToolActionEnum> getByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalized))
                .findFirst();
    }

    public static ToolActionEnum fromCode(String code) {
        return getByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("unsupported tool action: " + code));
    }
}
