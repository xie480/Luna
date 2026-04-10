package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 敏感等级枚举，用于表达能力调用的风险和权限管控强度。
 */
@Getter
public enum Sensitivity {
    /**
     * 低敏感等级，通常默认允许执行。
     */
    LOW("LOW", "低敏感等级"),
    /**
     * 中敏感等级，可能需要记录、审计或附加校验。
     */
    MEDIUM("MEDIUM", "中敏感等级"),
    /**
     * 高敏感等级，通常需要审批或严格拦截。
     */
    HIGH("HIGH", "高敏感等级");

    @EnumValue
    @JsonValue
    /**
     * 持久化和序列化使用的等级值。
     */
    private final String value;
    /**
     * 等级中文描述。
     */
    private final String desc;

    Sensitivity(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }
}
