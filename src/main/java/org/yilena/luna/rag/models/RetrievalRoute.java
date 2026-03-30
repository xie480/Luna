package org.yilena.luna.rag.models;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 检索流程路由枚举，标识检索应走的 pipeline 类型。
 */
public enum RetrievalRoute {
    SEARCH("search"),
    NATIVE("native"),
    MODULAR("modular"),
    AGENTIC("agentic");

    private final String value;

    RetrievalRoute(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<RetrievalRoute> fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(route -> route.value.equals(normalized)).findFirst();
    }

    public static List<RetrievalRoute> all() {
        return List.of(values());
    }
}
