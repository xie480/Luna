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
    LOW(0, "LOW", "低风险"),
    MEDIUM(1, "MEDIUM", "中风险"),
    HIGH(2, "HIGH", "高风险");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;
}
