package org.yilena.luna.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

@Getter
@AllArgsConstructor
public enum MemoryDomainEnum {
    TASK("TASK", "Task memory domain"),
    RELATION("RELATION", "Relational memory domain");

    private final String code;
    private final String desc;

    public static Optional<MemoryDomainEnum> getByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalized))
                .findFirst();
    }

    public static MemoryDomainEnum fromCode(String code) {
        return getByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("unsupported memory domain: " + code));
    }
}
