package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 计划节点类型枚举，用于区分执行图中不同职责的节点形态。
 */
@Getter
@AllArgsConstructor
public enum PlanNodeType {
    /**
     * 分析型节点，用于理解问题和拆分任务。
     */
    ANALYZE(0, "ANALYZE", "分析节点"),
    /**
     * 工具调用节点，用于执行具体能力。
     */
    TOOL(1, "TOOL", "工具节点"),
    /**
     * 校验节点，用于验证中间结果是否满足要求。
     */
    VALIDATE(3, "VALIDATE", "校验节点"),
    /**
     * 报告节点，用于汇总并生成最终报告。
     */
    REPORT(5, "REPORT", "报告节点"),
    /**
     * 代码处理节点，用于执行代码生成、修改或分析任务。
     */
    CODE(6, "CODE", "代码节点"),
    /**
     * 提示词节点，用于执行提示词治理或提示词能力。
     */
    PROMPT(7, "PROMPT", "提示词节点"),
    /**
     * 资源节点，用于读取或处理外部资源。
     */
    RESOURCE(8, "RESOURCE", "资源节点"),
    /**
     * 工作流节点，用于执行复合型工作流能力。
     */
    WORKFLOW(9, "WORKFLOW", "工作流节点");

    @EnumValue
    /**
     * 持久化到数据库中的数值类型码。
     */
    private final Integer code;

    @JsonValue
    /**
     * 对外序列化使用的类型值。
     */
    private final String value;

    /**
     * 类型的中文描述文案。
     */
    private final String desc;
}
