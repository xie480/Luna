package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * MCP 敏感度/权限等级枚举
 */
@Getter
public enum Sensitivity {
    LOW("LOW", "低敏感度(默认允许)"),
    MEDIUM("MEDIUM", "中敏感度(可能需要记录或轻度审计)"),
    HIGH("HIGH", "高敏感度(严格拦截或必须审批)");

    @EnumValue
    @JsonValue
    private final String value;
    private final String desc;

    Sensitivity(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }
}
