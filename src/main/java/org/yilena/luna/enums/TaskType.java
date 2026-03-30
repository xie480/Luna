package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任務類型枚舉
 */
@Getter
@AllArgsConstructor
public enum TaskType {
    REMINDER(0, "REMINDER", "提醒"), // 执行当前逻辑
    ACTION(1, "ACTION", "执行操作"), // 执行当前逻辑
    TODO(2, "TODO", "待办事项"); // 执行语句逻辑

    @EnumValue // 声明注解
    private final Integer code; // 声明成员字段

    @JsonValue // 声明注解
    private final String value; // 声明成员字段

    private final String desc; // 声明成员字段
} // 结束当前代码块
