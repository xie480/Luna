package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 节点模型提示
 */
@Getter
@AllArgsConstructor
public enum PlanModelHint {
    SMALL(0, "SMALL", "轻量模型"), // 执行当前逻辑
    MID(1, "MID", "中型模型"), // 执行当前逻辑
    BIG(2, "BIG", "大型模型"), // 执行当前逻辑
    FLASH(3, "FLASH", "快速模型"); // 执行语句逻辑

    @EnumValue // 声明注解
    private final Integer code; // 声明成员字段

    @JsonValue // 声明注解
    private final String value; // 声明成员字段

    private final String desc; // 声明成员字段
} // 结束当前代码块
