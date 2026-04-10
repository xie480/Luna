package org.yilena.luna.rag.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 该枚举用于定义可参与检索的数据源类型。
 */
public enum RetrievalSource {
    /**
     * 知识库数据源。
     */
    KNOWLEDGE("knowledge"),
    /**
     * 记忆数据源。
     */
    MEMORY("memory"),
    /**
     * 偏好数据源。
     */
    PREFERENCE("preference");

    /**
     * 对外暴露的数据源取值。
     */
    private final String value;

    RetrievalSource(String value) {
        this.value = value;
    }

    /**
     * 返回枚举的序列化值。
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * 从 JSON 文本反序列化数据源类型。
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RetrievalSource fromJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(source -> source.value.equals(normalized) || source.name().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown retrieval source: " + raw));
    }

    /**
     * 按取值安全解析数据源类型，未命中时返回空。
     */
    public static Optional<RetrievalSource> fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(source -> source.value.equals(normalized)).findFirst();
    }

    /**
     * 返回完整数据源集合，供配置和请求范围校验使用。
     */
    public static List<RetrievalSource> all() {
        return List.of(values());
    }
}
