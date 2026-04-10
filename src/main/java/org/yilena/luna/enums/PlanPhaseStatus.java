package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 计划阶段状态枚举，定义单个阶段在执行过程中的状态变化。
 */
@Getter
@AllArgsConstructor
public enum PlanPhaseStatus {
    /**
     * 阶段尚未开始执行。
     */
    PENDING(0, "PENDING", "待执行"),
    /**
     * 阶段正在执行中。
     */
    RUNNING(1, "RUNNING", "执行中"),
    /**
     * 阶段已成功完成。
     */
    SUCCESS(2, "SUCCESS", "成功"),
    /**
     * 阶段执行失败。
     */
    FAILED(3, "FAILED", "失败");

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
