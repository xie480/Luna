package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * Unified capability type for MCP orchestration.
 */
@Getter
public enum ResourceType {
    TOOL("TOOL", "Atomic tool"), // 执行当前逻辑
    SKILL("SKILL", "Legacy composite skill"), // 执行当前逻辑
    PROMPT("PROMPT", "Prompt template"), // 执行当前逻辑
    RESOURCE("RESOURCE", "Readable resource"), // 执行当前逻辑
    WORKFLOW("WORKFLOW", "Workflow template"); // 执行语句逻辑

    @EnumValue // 声明注解
    @JsonValue // 声明注解
    private final String value; // 声明成员字段
    private final String desc; // 声明成员字段

    ResourceType(String value, String desc) { // 开始新的代码块
        this.value = value; // 执行赋值操作
        this.desc = desc; // 执行赋值操作
    } // 结束当前代码块
} // 结束当前代码块
