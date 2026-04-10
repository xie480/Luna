package org.yilena.luna.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 记忆层枚举，用于区分工作记忆、语义记忆和情节记忆。
 */
@Getter
@AllArgsConstructor
public enum MemoryLayerEnum {
    /**
     * 工作记忆层。
     */
    WORKING("WORKING", "工作记忆层"),
    /**
     * 语义记忆层。
     */
    SEMANTIC("SEMANTIC", "语义记忆层"),
    /**
     * 情节记忆层。
     */
    EPISODIC("EPISODIC", "情节记忆层");

    /**
     * 记忆层编码。
     */
    private final String code;
    /**
     * 记忆层中文描述。
     */
    private final String desc;

    /**
     * 根据编码解析记忆层，可选返回。
     */
    public static Optional<MemoryLayerEnum> getByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalized))
                .findFirst();
    }

    /**
     * 根据编码解析记忆层，未命中时抛出异常。
     */
    public static MemoryLayerEnum fromCode(String code) {
        return getByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("unsupported memory layer: " + code));
    }
}
