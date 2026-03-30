package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 计划最终状态（用于报告/归档）
 */
@Getter
@AllArgsConstructor
public enum PlanFinalStatus {
    SUCCESS(0, "SUCCESS", "成功"), // 执行当前逻辑
    FAILED(1, "FAILED", "失败"), // 执行当前逻辑
    PARTIAL(2, "PARTIAL", "部分成功"), // 执行当前逻辑
    CANCELLED(3, "CANCELLED", "已取消"); // 执行语句逻辑

    @EnumValue // 声明注解
    private final Integer code; // 声明成员字段

    @JsonValue // 声明注解
    private final String value; // 声明成员字段

    private final String desc; // 声明成员字段
} // 结束当前代码块
