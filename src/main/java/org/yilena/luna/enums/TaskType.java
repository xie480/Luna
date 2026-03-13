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
    REMINDER(0, "提醒"),
    ACTION(1, "执行操作"),
    TODO(2, "待办事项");

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String desc;
}
