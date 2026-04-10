package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 日程任务状态枚举，用于标记提醒或待办任务的处理状态。
 */
@Getter
@AllArgsConstructor
public enum TaskStatus {
    /**
     * 待处理状态。
     */
    PENDING(0, "PENDING", "待处理"),
    /**
     * 已完成状态。
     */
    COMPLETED(1, "COMPLETED", "已完成"),
    /**
     * 已取消状态。
     */
    CANCELLED(2, "CANCELLED", "已取消"),
    /**
     * 已过期状态。
     */
    EXPIRED(3, "EXPIRED", "已过期");

    @EnumValue
    /**
     * 持久化到数据库中的数值编码。
     */
    private final Integer code;

    @JsonValue
    /**
     * 对外序列化使用的状态值。
     */
    private final String value;

    /**
     * 状态中文描述。
     */
    private final String desc;
}
