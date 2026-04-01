package org.yilena.luna.rag.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 证据角色分组，用于跨 source 的语义分桶。
 */
public enum EvidenceRole {
    FACT("fact"),
    EXPERIENCE("experience"),
    PREFERENCE("preference"),
    STRATEGY("strategy");

    private final String value;

    EvidenceRole(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static EvidenceRole fromJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(role -> role.value.equals(normalized) || role.name().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown evidence role: " + raw));
    }

    public static Optional<EvidenceRole> fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(role -> role.value.equals(normalized)).findFirst();
    }
}
