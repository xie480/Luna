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
    INFO(0, "INFO", "信息"), // 执行当前逻辑
    WARN(1, "WARN", "警告"), // 执行当前逻辑
    ERROR(2, "ERROR", "错误"); // 执行语句逻辑

    @EnumValue // 声明注解
    private final Integer code; // 声明成员字段

    @JsonValue // 声明注解
    private final String value; // 声明成员字段

    private final String desc; // 声明成员字段
} // 结束当前代码块
