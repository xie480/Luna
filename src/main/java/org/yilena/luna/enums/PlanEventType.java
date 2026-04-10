package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 计划事件类型枚举，用于标记计划编排和审批链路中的关键事件。
 */
@Getter
@AllArgsConstructor
public enum PlanEventType {
    /**
     * 计划已创建。
     */
    PLAN_CREATED(0, "PLAN_CREATED", "计划创建"),
    /**
     * 阶段开始执行。
     */
    PLAN_PHASE_STARTED(1, "PLAN_PHASE_STARTED", "阶段开始"),
    /**
     * 阶段结束执行。
     */
    PLAN_PHASE_FINISHED(2, "PLAN_PHASE_FINISHED", "阶段结束"),
    /**
     * 节点进入执行中。
     */
    PLAN_NODE_RUNNING(3, "PLAN_NODE_RUNNING", "节点执行中"),
    /**
     * 节点执行成功。
     */
    PLAN_NODE_SUCCESS(4, "PLAN_NODE_SUCCESS", "节点成功"),
    /**
     * 节点执行失败。
     */
    PLAN_NODE_FAILED(5, "PLAN_NODE_FAILED", "节点失败"),
    /**
     * 计划已重规划。
     */
    PLAN_REPLANNED(6, "PLAN_REPLANNED", "重新规划"),
    /**
     * 计划整体结束。
     */
    PLAN_FINISHED(7, "PLAN_FINISHED", "计划完成"),
    /**
     * 最终报告已生成。
     */
    PLAN_REPORT_READY(8, "PLAN_REPORT_READY", "报告已生成"),
    /**
     * 代码补丁已准备完成。
     */
    PLAN_CODE_PATCH_READY(9, "PLAN_CODE_PATCH_READY", "代码补丁已就绪"),
    /**
     * 测试结果已产生。
     */
    PLAN_TEST_RESULT(10, "PLAN_TEST_RESULT", "测试结果"),
    /**
     * 发起审批请求。
     */
    APPROVAL_REQUEST(11, "APPROVAL_REQUEST", "审批请求"),
    /**
     * 审批结果已返回。
     */
    APPROVAL_RESULT(12, "APPROVAL_RESULT", "审批结果"),
    /**
     * 计划检查点已创建。
     */
    PLAN_CHECKPOINT_CREATED(13, "PLAN_CHECKPOINT_CREATED", "检查点已创建"),
    /**
     * 蓝图校验通过。
     */
    PLAN_BLUEPRINT_VALIDATED(14, "PLAN_BLUEPRINT_VALIDATED", "蓝图校验通过"),
    /**
     * 蓝图校验失败。
     */
    PLAN_BLUEPRINT_INVALID(15, "PLAN_BLUEPRINT_INVALID", "蓝图校验失败");

    @EnumValue
    /**
     * 持久化到数据库中的数值编码。
     */
    private final Integer code;

    @JsonValue
    /**
     * 对外序列化使用的事件值。
     */
    private final String value;

    /**
     * 事件中文描述。
     */
    private final String desc;
}
