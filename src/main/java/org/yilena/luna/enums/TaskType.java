package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任務類型枚舉
 */
@Getter
@AllArgsConstructor
public enum TaskType {
    REMINDER("REMINDER", "提醒"),
    ACTION("ACTION", "执行操作"),
    TODO("TODO", "待办事项");

    @EnumValue
    private final String code;
    private final String desc;
}
