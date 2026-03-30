package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Plan node type.
 *
 * Keep legacy codes for compatibility with existing rows.
 */
@Getter
@AllArgsConstructor
public enum PlanNodeType {
    ANALYZE(0, "ANALYZE", "Analysis"), // 执行当前逻辑
    TOOL(1, "TOOL", "Tool call"), // 执行当前逻辑
    SKILL(2, "SKILL", "Legacy skill call"), // 执行当前逻辑
    VALIDATE(3, "VALIDATE", "Validation"), // 执行当前逻辑
    SUMMARIZE(4, "SUMMARIZE", "Legacy summarize"), // 执行当前逻辑
    REPORT(5, "REPORT", "Report"), // 执行当前逻辑
    CODE(6, "CODE", "Code task"), // 执行当前逻辑
    PROMPT(7, "PROMPT", "Prompt node"), // 执行当前逻辑
    RESOURCE(8, "RESOURCE", "Resource node"), // 执行当前逻辑
    WORKFLOW(9, "WORKFLOW", "Workflow node"); // 执行语句逻辑

    @EnumValue // 声明注解
    private final Integer code; // 声明成员字段

    @JsonValue // 声明注解
    private final String value; // 声明成员字段

    private final String desc; // 声明成员字段
} // 结束当前代码块
