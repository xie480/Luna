package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 计划事件日志级别
 */
@Getter
@AllArgsConstructor
public enum PlanEventLevel {
    INFO(0, "INFO", "信息"),
    WARN(1, "WARN", "警告"),
    ERROR(2, "ERROR", "错误");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;
}
