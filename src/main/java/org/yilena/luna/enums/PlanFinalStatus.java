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
    SUCCESS(0, "SUCCESS", "成功"),
    FAILED(1, "FAILED", "失败"),
    PARTIAL(2, "PARTIAL", "部分成功"),
    CANCELLED(3, "CANCELLED", "已取消");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;
}
