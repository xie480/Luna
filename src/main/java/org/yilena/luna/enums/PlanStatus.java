package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 计划运行状态枚举，定义计划实例在执行过程中的生命周期阶段。
 */
@Getter
@AllArgsConstructor
public enum PlanStatus {
    /**
     * 计划已创建但尚未开始执行。
     */
    PENDING(0, "PENDING", "待执行"),
    /**
     * 计划正在执行中。
     */
    RUNNING(1, "RUNNING", "执行中"),
    /**
     * 计划执行过程中等待用户审批。
     */
    WAITING_USER_APPROVAL(2, "WAITING_USER_APPROVAL", "等待用户审批"),
    /**
     * 计划已成功完成。
     */
    SUCCESS(3, "SUCCESS", "成功"),
    /**
     * 计划执行失败。
     */
    FAILED(4, "FAILED", "失败"),
    /**
     * 计划被主动取消。
     */
    CANCELLED(5, "CANCELLED", "已取消");

    @EnumValue
    /**
     * 持久化到数据库中的数值状态码。
     */
    private final Integer code;

    @JsonValue
    /**
     * 对外序列化使用的状态值。
     */
    private final String value;

    /**
     * 状态的中文描述文案。
     */
    private final String desc;
}
