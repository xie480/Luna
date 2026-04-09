package org.yilena.luna.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

@Getter
@AllArgsConstructor
public enum MemoryLayerEnum {
    WORKING("WORKING", "Working memory layer"),
    SEMANTIC("SEMANTIC", "Semantic memory layer"),
    EPISODIC("EPISODIC", "Episodic memory layer");

    private final String code;
    private final String desc;

    public static Optional<MemoryLayerEnum> getByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalized))
                .findFirst();
    }

    public static MemoryLayerEnum fromCode(String code) {
        return getByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("unsupported memory layer: " + code));
    }
}
