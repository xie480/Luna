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
    FACT(0, "FACT", "客观事实"),
    PREFERENCE(1, "PREFERENCE", "用户偏好"),
    SUMMARY(2, "SUMMARY", "对话摘要"),
    REFLECTION(3, "REFLECTION", "自我反思");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;
}
