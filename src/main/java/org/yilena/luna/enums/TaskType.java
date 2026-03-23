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
    REMINDER(0, "REMINDER", "提醒"),
    ACTION(1, "ACTION", "执行操作"),
    TODO(2, "TODO", "待办事项");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;
}
