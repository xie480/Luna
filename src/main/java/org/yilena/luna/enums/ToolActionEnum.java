package org.yilena.luna.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 工具操作类型枚举，用于统一定义增删改查类工具动作。
 */
@Getter
@AllArgsConstructor
public enum ToolActionEnum {
    /**
     * 新增动作。
     */
    INSERT("INSERT", "新增"),
    /**
     * 查询动作。
     */
    QUERY("QUERY", "查询"),
    /**
     * 更新动作。
     */
    UPDATE("UPDATE", "更新"),
    /**
     * 删除动作。
     */
    DELETE("DELETE", "删除");

    /**
     * 操作编码。
     */
    private final String code;
    /**
     * 操作中文描述。
     */
    private final String desc;

    /**
     * 根据编码解析操作类型，可选返回。
     */
    public static Optional<ToolActionEnum> getByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalized))
                .findFirst();
    }

    /**
     * 根据编码解析操作类型，未命中时抛出异常。
     */
    public static ToolActionEnum fromCode(String code) {
        return getByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("unsupported tool action: " + code));
    }
}
