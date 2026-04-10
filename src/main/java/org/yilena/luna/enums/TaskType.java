package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 日程任务类型枚举，用于区分提醒、动作执行和待办事项。
 */
@Getter
@AllArgsConstructor
public enum TaskType {
    /**
     * 提醒类任务。
     */
    REMINDER(0, "REMINDER", "提醒"),
    /**
     * 动作执行类任务。
     */
    ACTION(1, "ACTION", "执行操作"),
    /**
     * 待办事项类任务。
     */
    TODO(2, "TODO", "待办事项");

    @EnumValue
    /**
     * 持久化到数据库中的数值编码。
     */
    private final Integer code;

    @JsonValue
    /**
     * 对外序列化使用的类型值。
     */
    private final String value;

    /**
     * 类型中文描述。
     */
    private final String desc;
}
