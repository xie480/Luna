package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 报告打开结果枚举，表示计划报告生成后尝试唤起浏览器的执行结果。
 */
@Getter
@AllArgsConstructor
public enum PlanOpenResult {
    /**
     * 报告已成功打开。
     */
    SUCCESS(0, "SUCCESS", "打开成功"),
    /**
     * 报告打开失败。
     */
    FAILED(1, "FAILED", "打开失败");

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
