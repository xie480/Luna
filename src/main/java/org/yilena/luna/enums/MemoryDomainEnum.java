package org.yilena.luna.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 记忆域枚举，用于区分任务记忆和关系记忆两个大类。
 */
@Getter
@AllArgsConstructor
public enum MemoryDomainEnum {
    /**
     * 任务相关记忆域。
     */
    TASK("TASK", "任务记忆域"),
    /**
     * 关系相关记忆域。
     */
    RELATION("RELATION", "关系记忆域");

    /**
     * 记忆域编码。
     */
    private final String code;
    /**
     * 记忆域中文描述。
     */
    private final String desc;

    /**
     * 根据编码解析记忆域，可选返回。
     */
    public static Optional<MemoryDomainEnum> getByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalized))
                .findFirst();
    }

    /**
     * 根据编码解析记忆域，未命中时抛出异常。
     */
    public static MemoryDomainEnum fromCode(String code) {
        return getByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("unsupported memory domain: " + code));
    }
}
