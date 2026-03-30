package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 计划运行状态
 */
@Getter
@AllArgsConstructor
public enum PlanStatus {
    PENDING(0, "PENDING", "待执行"), // 执行当前逻辑
    RUNNING(1, "RUNNING", "执行中"), // 执行当前逻辑
    WAITING_USER_APPROVAL(2, "WAITING_USER_APPROVAL", "等待用户审批"), // 执行当前逻辑
    SUCCESS(3, "SUCCESS", "成功"), // 执行当前逻辑
    FAILED(4, "FAILED", "失败"), // 执行当前逻辑
    CANCELLED(5, "CANCELLED", "已取消"); // 执行语句逻辑

    @EnumValue // 声明注解
    private final Integer code; // 声明成员字段

    @JsonValue // 声明注解
    private final String value; // 声明成员字段

    private final String desc; // 声明成员字段
} // 结束当前代码块
