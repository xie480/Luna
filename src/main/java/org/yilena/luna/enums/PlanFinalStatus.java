package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 计划最终状态枚举，定义计划归档和报告展示时使用的最终结果分类。
 */
@Getter
@AllArgsConstructor
public enum PlanFinalStatus {
    /**
     * 计划整体成功完成。
     */
    SUCCESS(0, "SUCCESS", "成功"),
    /**
     * 计划整体执行失败。
     */
    FAILED(1, "FAILED", "失败"),
    /**
     * 计划部分完成，存在未完成或失败环节。
     */
    PARTIAL(2, "PARTIAL", "部分成功"),
    /**
     * 计划被主动取消。
     */
    CANCELLED(3, "CANCELLED", "已取消");

    @EnumValue
    /**
     * 持久化到数据库中的数值状态码。
     */
    private final Integer code;

    @JsonValue
    /**
     * 对外序列化使用的状态值。
     */
    private final String value;

    /**
     * 状态的中文描述文案。
     */
    private final String desc;
}
