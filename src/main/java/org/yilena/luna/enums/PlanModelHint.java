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
    SMALL(0, "SMALL", "轻量模型"),
    MID(1, "MID", "中型模型"),
    BIG(2, "BIG", "大型模型"),
    FLASH(3, "FLASH", "快速模型");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;
}
