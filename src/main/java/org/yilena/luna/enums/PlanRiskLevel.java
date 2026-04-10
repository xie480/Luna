package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 计划风险等级枚举，用于表达节点或阶段的执行风险高低。
 */
@Getter
@AllArgsConstructor
public enum PlanRiskLevel {
    /**
     * 低风险，通常无需额外审批或保护措施。
     */
    LOW(0, "LOW", "低风险"),
    /**
     * 中风险，可能需要额外校验或谨慎执行。
     */
    MEDIUM(1, "MEDIUM", "中风险"),
    /**
     * 高风险，通常需要审批或强保护策略。
     */
    HIGH(2, "HIGH", "高风险");

    @EnumValue
    /**
     * 持久化到数据库中的数值风险码。
     */
    private final Integer code;

    @JsonValue
    /**
     * 对外序列化使用的风险值。
     */
    private final String value;

    /**
     * 风险等级的中文描述文案。
     */
    private final String desc;
}
