package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 来源类型枚举，用于区分知识或内容的来源渠道。
 */
@Getter
@AllArgsConstructor
public enum SourceType {
    /**
     * 本地文件来源。
     */
    FILE(0, "FILE", "本地文件"),
    /**
     * 网络搜索来源。
     */
    WEB_SEARCH(1, "WEB_SEARCH", "网络搜索"),
    /**
     * 人工手动录入来源。
     */
    MANUAL_INPUT(2, "MANUAL_INPUT", "手动录入");

    @EnumValue
    /**
     * 持久化到数据库中的数值编码。
     */
    private final Integer code;

    @JsonValue
    /**
     * 对外序列化使用的来源值。
     */
    private final String value;

    /**
     * 来源中文描述。
     */
    private final String desc;

    /**
     * 根据数值编码解析来源类型。
     */
    public static Optional<SourceType> getByCode(Integer code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(item -> item.code.equals(code))
                .findFirst();
    }

    /**
     * 根据字符串值解析来源类型。
     */
    public static Optional<SourceType> getByValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.value.equals(normalized))
                .findFirst();
    }

    /**
     * 同时兼容数值编码和字符串值的解析方式。
     */
    public static Optional<SourceType> fromCodeOrValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String text = raw.trim();
        try {
            return getByCode(Integer.parseInt(text));
        } catch (NumberFormatException ignore) {
            return getByValue(text);
        }
    }
}
