package org.yilena.luna.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

/**
 * 工具执行状态枚举，用于统一标准化不同来源的工具执行结果。
 */
@Getter
@AllArgsConstructor
public enum ToolStatusEnum {
    /**
     * 工具调用成功。
     */
    SUCCESS("SUCCESS", "调用成功"),
    /**
     * 工具调用仍在处理中。
     */
    PENDING("PENDING", "处理中"),
    /**
     * 工具调用失败。
     */
    FAILED("FAILED", "调用失败"),
    /**
     * 工具状态未知。
     */
    UNKNOWN("UNKNOWN", "状态未知");

    @JsonValue
    /**
     * 对外序列化使用的状态编码。
     */
    private final String code;

    /**
     * 状态中文描述。
     */
    private final String desc;

    /**
     * 根据编码解析工具状态，未命中时回退为 UNKNOWN。
     */
    public static ToolStatusEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return UNKNOWN;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalized))
                .findFirst()
                .orElse(UNKNOWN);
    }

    /**
     * 根据原始状态文本做兼容映射。
     */
    public static ToolStatusEnum fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "OK" -> SUCCESS;
            case "RUNNING" -> PENDING;
            case "ERROR" -> FAILED;
            default -> fromCode(normalized);
        };
    }

    /**
     * 返回全部状态编码数组，供过滤条件或下游配置使用。
     */
    public static String[] codes() {
        return Arrays.stream(values())
                .map(ToolStatusEnum::getCode)
                .toArray(String[]::new);
    }
}
