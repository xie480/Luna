package org.yilena.luna.rag.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 该枚举用于定义证据在语义层面的角色分组，便于跨来源统一归类与消费。
 */
public enum EvidenceRole {
    /**
     * 客观事实型证据。
     */
    FACT("fact"),
    /**
     * 经验经历型证据。
     */
    EXPERIENCE("experience"),
    /**
     * 偏好习惯型证据。
     */
    PREFERENCE("preference"),
    /**
     * 策略建议型证据。
     */
    STRATEGY("strategy");

    /**
     * 对外暴露的枚举取值。
     */
    private final String value;

    EvidenceRole(String value) {
        this.value = value;
    }

    /**
     * 返回枚举对外序列化时使用的值。
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * 从 JSON 文本反序列化证据角色。
     */
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

    /**
     * 按取值安全解析证据角色，未命中时返回空。
     */
    public static Optional<EvidenceRole> fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(role -> role.value.equals(normalized)).findFirst();
    }
}
