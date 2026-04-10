package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 计划事件等级枚举，用于标记计划事件日志的重要程度。
 */
@Getter
@AllArgsConstructor
public enum PlanEventLevel {
    /**
     * 普通信息事件。
     */
    INFO(0, "INFO", "信息"),
    /**
     * 警告事件。
     */
    WARN(1, "WARN", "警告"),
    /**
     * 错误事件。
     */
    ERROR(2, "ERROR", "错误");

    @EnumValue
    /**
     * 持久化到数据库中的数值编码。
     */
    private final Integer code;

    @JsonValue
    /**
     * 对外序列化使用的等级值。
     */
    private final String value;

    /**
     * 等级中文描述。
     */
    private final String desc;
}
