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
    PENDING(0, "PENDING", "待执行"),
    RUNNING(1, "RUNNING", "执行中"),
    WAITING_USER_APPROVAL(2, "WAITING_USER_APPROVAL", "等待用户审批"),
    SUCCESS(3, "SUCCESS", "成功"),
    FAILED(4, "FAILED", "失败"),
    CANCELLED(5, "CANCELLED", "已取消");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;
}
