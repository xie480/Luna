package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 記憶類型枚舉
 */
@Getter
@AllArgsConstructor
public enum MemoryType {
    FACT(0, "客观事实"),
    PREFERENCE(1, "用户偏好"),
    SUMMARY(2, "对话摘要"),
    REFLECTION(3, "自我反思");

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String desc;
}
