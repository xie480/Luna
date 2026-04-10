package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 节点模型提示枚举，用于为不同节点提供推荐的模型规格。
 */
@Getter
@AllArgsConstructor
public enum PlanModelHint {
    /**
     * 轻量模型，适合低成本快速任务。
     */
    SMALL(0, "SMALL", "轻量模型"),
    /**
     * 中型模型，适合复杂度适中的任务。
     */
    MID(1, "MID", "中型模型"),
    /**
     * 大型模型，适合高复杂度推理任务。
     */
    BIG(2, "BIG", "大型模型"),
    /**
     * 极速模型，适合高时效要求场景。
     */
    FLASH(3, "FLASH", "快速模型");

    @EnumValue
    /**
     * 持久化到数据库中的数值提示码。
     */
    private final Integer code;

    @JsonValue
    /**
     * 对外序列化使用的提示值。
     */
    private final String value;

    /**
     * 模型提示的中文描述文案。
     */
    private final String desc;
}
