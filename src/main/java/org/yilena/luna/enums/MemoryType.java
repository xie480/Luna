package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 記憶類型枚舉
 */
@Getter
@AllArgsConstructor
public enum MemoryType {
    FACT("FACT", "客观事实"),
    PREFERENCE("PREFERENCE", "用户偏好"),
    SUMMARY("SUMMARY", "对话摘要"),
    REFLECTION("REFLECTION", "自我反思");

    @EnumValue
    private final String code;
    private final String desc;
}
