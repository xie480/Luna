package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 节点执行状态
 */
@Getter
@AllArgsConstructor
public enum PlanNodeStatus {
    PENDING(0, "PENDING", "待执行"),
    RUNNING(1, "RUNNING", "执行中"),
    SUCCESS(2, "SUCCESS", "成功"),
    FAILED(3, "FAILED", "失败"),
    BLOCKED(4, "BLOCKED", "阻塞"),
    APPROVAL_PENDING(5, "APPROVAL_PENDING", "等待审批"),
    SKIPPED(6, "SKIPPED", "已跳过");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;
}
