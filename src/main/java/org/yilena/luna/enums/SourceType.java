package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

@Getter
@AllArgsConstructor
public enum SourceType {
    FILE(0, "FILE", "local file"),
    WEB_SEARCH(1, "WEB_SEARCH", "web search"),
    MANUAL_INPUT(2, "MANUAL_INPUT", "manual input");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;

    public static Optional<SourceType> getByCode(Integer code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(item -> item.code.equals(code))
                .findFirst();
    }

    public static Optional<SourceType> getByValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.value.equals(normalized))
                .findFirst();
    }

    public static Optional<SourceType> fromCodeOrValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String text = raw.trim();
        try {
            return getByCode(Integer.parseInt(text));
        } catch (NumberFormatException ignore) {
            return getByValue(text);
        }
    }
}
