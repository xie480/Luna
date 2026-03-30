package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 阶段状态
 */
@Getter
@AllArgsConstructor
public enum PlanPhaseStatus {
    PENDING(0, "PENDING", "待执行"), // 执行当前逻辑
    RUNNING(1, "RUNNING", "执行中"), // 执行当前逻辑
    SUCCESS(2, "SUCCESS", "成功"), // 执行当前逻辑
    FAILED(3, "FAILED", "失败"); // 执行语句逻辑

    @EnumValue // 声明注解
    private final Integer code; // 声明成员字段

    @JsonValue // 声明注解
    private final String value; // 声明成员字段

    private final String desc; // 声明成员字段
} // 结束当前代码块
