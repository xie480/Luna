package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Plan node type for MCP-native orchestration.
 */
@Getter
@AllArgsConstructor
public enum PlanNodeType {
    ANALYZE(0, "ANALYZE", "Analysis"),
    TOOL(1, "TOOL", "Tool call"),
    VALIDATE(3, "VALIDATE", "Validation"),
    REPORT(5, "REPORT", "Report"),
    CODE(6, "CODE", "Code task"),
    PROMPT(7, "PROMPT", "Prompt node"),
    RESOURCE(8, "RESOURCE", "Resource node"),
    WORKFLOW(9, "WORKFLOW", "Workflow node");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;
}
