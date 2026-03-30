package org.yilena.luna.rag.models;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 检索数据源枚举，定义可参与召回的语料域。
 */
public enum RetrievalSource {
    KNOWLEDGE("knowledge"),
    MEMORY("memory"),
    PREFERENCE("preference");

    private final String value;

    RetrievalSource(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<RetrievalSource> fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        // 统一做大小写与空白标准化后再匹配枚举值。
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(source -> source.value.equals(normalized)).findFirst();
    }

    public static List<RetrievalSource> all() {
        // 返回完整数据源集合，供策略层遍历使用。
        return List.of(values());
    }
}
