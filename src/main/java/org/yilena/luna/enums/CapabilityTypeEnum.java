package org.yilena.luna.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

/**
 * 能力类型枚举，用于区分 MCP 目录中的不同能力形态。
 */
@Getter
@AllArgsConstructor
public enum CapabilityTypeEnum {
    /**
     * 提示词能力。
     */
    PROMPT("PROMPT", "提示词能力"),
    /**
     * 资源能力。
     */
    RESOURCE("RESOURCE", "资源能力"),
    /**
     * 工作流能力。
     */
    WORKFLOW("WORKFLOW", "工作流能力"),
    /**
     * 工具能力。
     */
    TOOL("TOOL", "工具能力"),
    /**
     * 策略能力。
     */
    STRATEGY("STRATEGY", "策略能力"),
    /**
     * 未识别的能力类型。
     */
    UNKNOWN("UNKNOWN", "未知能力");

    @JsonValue
    /**
     * 对外序列化使用的类型编码。
     */
    private final String code;

    /**
     * 类型中文描述。
     */
    private final String desc;

    /**
     * 根据编码解析能力类型，无法识别时回退为 UNKNOWN。
     */
    public static CapabilityTypeEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return UNKNOWN;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalized))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
