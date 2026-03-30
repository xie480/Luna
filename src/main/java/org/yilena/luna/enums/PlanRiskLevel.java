package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 风险等级
 */
@Getter
@AllArgsConstructor
public enum PlanRiskLevel {
    LOW(0, "LOW", "低风险"), // 执行当前逻辑
    MEDIUM(1, "MEDIUM", "中风险"), // 执行当前逻辑
    HIGH(2, "HIGH", "高风险"); // 执行语句逻辑

    @EnumValue // 声明注解
    private final Integer code; // 声明成员字段

    @JsonValue // 声明注解
    private final String value; // 声明成员字段

    private final String desc; // 声明成员字段
} // 结束当前代码块
