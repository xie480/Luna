package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 记忆类型枚举，用于区分长期记忆表中不同语义类别的数据。
 */
@Getter
@AllArgsConstructor
public enum MemoryType {
    /**
     * 客观事实记忆。
     */
    FACT(0, "FACT", "客观事实"),
    /**
     * 用户偏好记忆。
     */
    PREFERENCE(1, "PREFERENCE", "用户偏好"),
    /**
     * 对话摘要记忆。
     */
    SUMMARY(2, "SUMMARY", "对话摘要"),
    /**
     * 自我反思记忆。
     */
    REFLECTION(3, "REFLECTION", "自我反思");

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
