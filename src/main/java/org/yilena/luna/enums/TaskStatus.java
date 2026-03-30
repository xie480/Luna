package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任務狀態枚舉
 */
@Getter
@AllArgsConstructor
public enum TaskStatus {
    PENDING(0, "PENDING", "待处理"), // 执行当前逻辑
    COMPLETED(1, "COMPLETED", "已完成"), // 执行当前逻辑
    CANCELLED(2, "CANCELLED", "已取消"), // 执行当前逻辑
    EXPIRED(3, "EXPIRED", "已过期"); // 执行语句逻辑

    @EnumValue // 声明注解
    private final Integer code; // 声明成员字段

    @JsonValue // 声明注解
    private final String value; // 声明成员字段

    private final String desc; // 声明成员字段
} // 结束当前代码块
