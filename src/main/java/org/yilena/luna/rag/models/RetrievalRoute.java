package org.yilena.luna.rag.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 该枚举用于定义检索流程的路由类型，标识请求应进入哪种 pipeline。
 */
public enum RetrievalRoute {
    /**
     * 轻量检索路由。
     */
    SEARCH("search"),
    /**
     * 原生检索路由。
     */
    NATIVE("native"),
    /**
     * 模块化检索路由。
     */
    MODULAR("modular"),
    /**
     * Agentic 检索路由。
     */
    AGENTIC("agentic");

    /**
     * 对外暴露的路由取值。
     */
    private final String value;

    RetrievalRoute(String value) {
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
     * 从 JSON 文本反序列化检索路由。
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RetrievalRoute fromJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(route -> route.value.equals(normalized) || route.name().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown retrieval route: " + raw));
    }

    /**
     * 按取值安全解析检索路由，未命中时返回空。
     */
    public static Optional<RetrievalRoute> fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(route -> route.value.equals(normalized)).findFirst();
    }

    /**
     * 返回完整路由集合，供请求范围校验和下拉配置使用。
     */
    public static List<RetrievalRoute> all() {
        return List.of(values());
    }
}
