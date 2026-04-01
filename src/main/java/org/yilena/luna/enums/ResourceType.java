package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * Unified capability type for MCP orchestration.
 */
@Getter
public enum ResourceType {
    TOOL("TOOL", "Atomic tool"),
    PROMPT("PROMPT", "Prompt template"),
    RESOURCE("RESOURCE", "Readable resource"),
    WORKFLOW("WORKFLOW", "Workflow template"),
    STRATEGY("STRATEGY", "Policy strategy");

    @EnumValue
    @JsonValue
    private final String value;
    private final String desc;

    ResourceType(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }
}
