package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 计划事件类型
 */
@Getter
@AllArgsConstructor
public enum PlanEventType {
    PLAN_CREATED(0, "PLAN_CREATED", "计划创建"), // 执行当前逻辑
    PLAN_PHASE_STARTED(1, "PLAN_PHASE_STARTED", "阶段开始"), // 执行当前逻辑
    PLAN_PHASE_FINISHED(2, "PLAN_PHASE_FINISHED", "阶段结束"), // 执行当前逻辑
    PLAN_NODE_RUNNING(3, "PLAN_NODE_RUNNING", "节点执行中"), // 执行当前逻辑
    PLAN_NODE_SUCCESS(4, "PLAN_NODE_SUCCESS", "节点成功"), // 执行当前逻辑
    PLAN_NODE_FAILED(5, "PLAN_NODE_FAILED", "节点失败"), // 执行当前逻辑
    PLAN_REPLANNED(6, "PLAN_REPLANNED", "重规划"), // 执行当前逻辑
    PLAN_FINISHED(7, "PLAN_FINISHED", "计划完成"), // 执行当前逻辑
    PLAN_REPORT_READY(8, "PLAN_REPORT_READY", "报告已生成"), // 执行当前逻辑
    PLAN_CODE_PATCH_READY(9, "PLAN_CODE_PATCH_READY", "代码补丁已就绪"), // 执行当前逻辑
    PLAN_TEST_RESULT(10, "PLAN_TEST_RESULT", "测试结果"), // 执行当前逻辑
    APPROVAL_REQUEST(11, "APPROVAL_REQUEST", "审批请求"), // 执行当前逻辑
    APPROVAL_RESULT(12, "APPROVAL_RESULT", "审批结果"), // 执行当前逻辑
    PLAN_CHECKPOINT_CREATED(13, "PLAN_CHECKPOINT_CREATED", "计划检查点已创建"), // 执行当前逻辑
    PLAN_BLUEPRINT_VALIDATED(14, "PLAN_BLUEPRINT_VALIDATED", "蓝图校验通过"), // 执行当前逻辑
    PLAN_BLUEPRINT_INVALID(15, "PLAN_BLUEPRINT_INVALID", "蓝图校验失败"); // 执行语句逻辑

    @EnumValue // 声明注解
    private final Integer code; // 声明成员字段

    @JsonValue // 声明注解
    private final String value; // 声明成员字段

    private final String desc; // 声明成员字段
} // 结束当前代码块
