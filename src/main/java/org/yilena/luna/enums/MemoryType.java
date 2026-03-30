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
    FACT(0, "FACT", "客观事实"), // 执行当前逻辑
    PREFERENCE(1, "PREFERENCE", "用户偏好"), // 执行当前逻辑
    SUMMARY(2, "SUMMARY", "对话摘要"), // 执行当前逻辑
    REFLECTION(3, "REFLECTION", "自我反思"); // 执行语句逻辑

    @EnumValue // 声明注解
    private final Integer code; // 声明成员字段

    @JsonValue // 声明注解
    private final String value; // 声明成员字段

    private final String desc; // 声明成员字段
} // 结束当前代码块
