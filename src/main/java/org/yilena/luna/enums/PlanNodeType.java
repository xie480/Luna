package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任务节点类型
 */
@Getter
@AllArgsConstructor
public enum PlanNodeType {
    ANALYZE(0, "ANALYZE", "分析"),
    TOOL(1, "TOOL", "工具调用"),
    SKILL(2, "SKILL", "技能调用"),
    VALIDATE(3, "VALIDATE", "校验"),
    SUMMARIZE(4, "SUMMARIZE", "总结"),
    REPORT(5, "REPORT", "报告"),
    CODE(6, "CODE", "代码任务");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;
}
