package org.yilena.luna.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

/**
 * 日程更新模式枚举，用于区分全量覆盖和局部更新。
 */
@Getter
@AllArgsConstructor
public enum ScheduleUpdateModeEnum {
    /**
     * 全量更新，未传字段通常会被覆盖。
     */
    PUT("PUT", "全量更新"),
    /**
     * 局部更新，仅修改提供的字段。
     */
    PATCH("PATCH", "局部更新");

    @JsonValue
    /**
     * 对外序列化使用的模式编码。
     */
    private final String code;

    /**
     * 模式中文描述。
     */
    private final String desc;

    /**
     * 根据编码解析更新模式，未命中时抛出异常。
     */
    public static ScheduleUpdateModeEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("schedule update mode is required");
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported schedule update mode: " + code));
    }
}
