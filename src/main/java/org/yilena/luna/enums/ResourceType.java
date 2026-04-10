package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 统一资源类型枚举，用于区分能力目录中的工具、提示词、资源和工作流。
 */
@Getter
public enum ResourceType {
    /**
     * 原子工具能力。
     */
    TOOL("TOOL", "工具"),
    /**
     * 提示词模板能力。
     */
    PROMPT("PROMPT", "提示词模板"),
    /**
     * 可读取资源能力。
     */
    RESOURCE("RESOURCE", "可读取资源"),
    /**
     * 复合工作流能力。
     */
    WORKFLOW("WORKFLOW", "工作流模板"),
    /**
     * 策略或规则类能力。
     */
    STRATEGY("STRATEGY", "策略能力");

    @EnumValue
    @JsonValue
    /**
     * 持久化和序列化使用的类型值。
     */
    private final String value;
    /**
     * 类型中文描述。
     */
    private final String desc;

    ResourceType(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }
}
