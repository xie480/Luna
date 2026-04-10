package org.yilena.luna.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

/**
 * 日程操作类型枚举，用于定义日程管理工具支持的动作。
 */
@Getter
@AllArgsConstructor
public enum ScheduleActionEnum {
    /**
     * 新增日程任务。
     */
    INSERT("INSERT", "新增任务"),
    /**
     * 查询日程任务。
     */
    QUERY("QUERY", "查询任务"),
    /**
     * 更新日程任务。
     */
    UPDATE("UPDATE", "更新任务"),
    /**
     * 删除日程任务。
     */
    DELETE("DELETE", "删除任务");

    @JsonValue
    /**
     * 对外序列化使用的操作编码。
     */
    private final String code;

    /**
     * 操作中文描述。
     */
    private final String desc;

    /**
     * 根据编码解析日程操作，未命中时抛出异常。
     */
    public static ScheduleActionEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("schedule action is required");
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported schedule action: " + code));
    }
}
