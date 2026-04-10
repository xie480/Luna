package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 计划节点状态枚举，定义单个节点在执行图中的运行状态。
 */
@Getter
@AllArgsConstructor
public enum PlanNodeStatus {
    /**
     * 节点尚未开始执行。
     */
    PENDING(0, "PENDING", "待执行"),
    /**
     * 节点正在执行中。
     */
    RUNNING(1, "RUNNING", "执行中"),
    /**
     * 节点已成功完成。
     */
    SUCCESS(2, "SUCCESS", "成功"),
    /**
     * 节点执行失败。
     */
    FAILED(3, "FAILED", "失败"),
    /**
     * 节点被上游条件或治理策略阻塞。
     */
    BLOCKED(4, "BLOCKED", "阻塞"),
    /**
     * 节点等待人工审批后继续执行。
     */
    APPROVAL_PENDING(5, "APPROVAL_PENDING", "等待审批"),
    /**
     * 节点被策略跳过，不再执行。
     */
    SKIPPED(6, "SKIPPED", "已跳过");

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
